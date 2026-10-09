/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.xmlvalidation.schema;

import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.resource.api.ResourceManager;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.validation.Schema;

/**
 * Holds a compiled XSD for a policy instance. Registry schemas re-resolve on each ensureReady so
 * resource byte-cache TTL (e.g. floating {@code branch=latest}) can pick up new content; JAXP
 * compile is still skipped when the digest is unchanged ({@link #SCHEMA_BY_DIGEST}).
 */
public final class CompiledXsdSchemaHolder {

    /**
     * API-classloader-scoped: each API's policy ClassLoader has its own static map.
     * Digests are not evicted when a floating version moves on: another policy instance in this
     * classloader may still be validating against the previous bytes.
     */
    private static final ConcurrentHashMap<String, Schema> SCHEMA_BY_DIGEST = new ConcurrentHashMap<>();

    private final XmlValidationPolicyConfiguration configuration;
    private final Object lock = new Object();
    private volatile CompiledXsdSchema compiledSchema;
    private volatile String compiledBundleDigest;
    private volatile XsdSchemaResolutionException initializationFailure;
    private volatile Maybe<CompiledXsdSchema> inFlight;

    private CompiledXsdSchemaHolder(XmlValidationPolicyConfiguration configuration) {
        this.configuration = configuration;
    }

    public static CompiledXsdSchemaHolder forConfiguration(XmlValidationPolicyConfiguration configuration) {
        CompiledXsdSchemaHolder holder = new CompiledXsdSchemaHolder(configuration);
        if (configuration.getSchemaSource() == SchemaSource.INLINE) {
            String content = configuration.getXsdSchema();
            String digest = sha256(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
            Schema schema = SCHEMA_BY_DIGEST.computeIfAbsent(digest, d -> XsdSchemaCompiler.compile(content, SchemaSource.INLINE).schema());
            holder.compiledSchema = new CompiledXsdSchema(schema);
        }
        return holder;
    }

    public Completable ensureReady(HttpPlainExecutionContext ctx) {
        if (configuration.getSchemaSource() == SchemaSource.INLINE) {
            return Completable.complete();
        }
        if (initializationFailure != null) {
            return Completable.error(initializationFailure);
        }
        ResourceManager resourceManager = ctx.getComponent(ResourceManager.class);
        return load(resourceManager).ignoreElement();
    }

    private Maybe<CompiledXsdSchema> load(ResourceManager resourceManager) {
        Maybe<CompiledXsdSchema> cached = inFlight;
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            cached = inFlight;
            if (cached != null) {
                return cached;
            }
            if (initializationFailure != null) {
                return Maybe.error(initializationFailure);
            }
            XsdSchemaResolver resolver = XsdSchemaResolverFactory.create(configuration, resourceManager, this);
            cached = resolver
                .resolveReactive()
                .doOnSuccess(schema -> {
                    synchronized (lock) {
                        compiledSchema = schema;
                    }
                })
                .doOnError(this::recordFailure)
                .doFinally(() -> {
                    synchronized (lock) {
                        inFlight = null;
                    }
                })
                .cache();
            inFlight = cached;
            return cached;
        }
    }

    private void recordFailure(Throwable error) {
        XsdSchemaResolutionException ex;
        if (error instanceof XsdSchemaResolutionException xsre) {
            ex = xsre;
        } else {
            ex = new XsdSchemaResolutionException(
                "Schema resolution failed: " + error.getMessage(),
                SchemaSource.REGISTRY,
                XsdSchemaResolutionException.RegistryCoordinates.of(
                    configuration.getRegistryResource(),
                    configuration.getGroupId(),
                    configuration.getArtifactId(),
                    configuration.getVersion()
                ),
                error
            );
        }
        synchronized (lock) {
            // Resource owns outage backoff. Policy must not permanently pin coordinate misses
            // or compile failures — registry content / coordinates can be fixed without redeploy.
            if (ex.getFailureKind() == XsdSchemaResolutionException.FailureKind.CONFIGURATION) {
                if (initializationFailure == null) {
                    initializationFailure = ex;
                }
            } else {
                initializationFailure = null;
            }
            inFlight = null;
        }
    }

    /**
     * Reuse the compiled schema when the resource returns the same bundle digest. A different digest
     * compiles again; {@link XsdSchemaCompiler} still recomputes the digest from bytes on that path.
     */
    CompiledXsdSchema compiledFrom(io.gravitee.resource.schema_registry.api.ArtifactSchemaBundle bundle) {
        String reported = bundle == null ? null : bundle.digest();
        CompiledXsdSchema current = compiledSchema;
        if (current != null && reported != null && !reported.isBlank() && reported.equals(compiledBundleDigest)) {
            return current;
        }
        CompiledXsdSchema compiled = XsdSchemaCompiler.compile(bundle);
        synchronized (lock) {
            compiledSchema = compiled;
            // A blank digest cannot be trusted as a cache key for the next request.
            compiledBundleDigest = reported != null && !reported.isBlank() ? reported : null;
        }
        return compiled;
    }

    /**
     * Same as {@link #compiledFrom}, but schedules JAXP compile on the computation pool only when the
     * digest changed. Unchanged digests stay on the calling thread (event loop after a cache hit).
     */
    Maybe<CompiledXsdSchema> compiledFromReactive(io.gravitee.resource.schema_registry.api.ArtifactSchemaBundle bundle) {
        String reported = bundle == null ? null : bundle.digest();
        CompiledXsdSchema current = compiledSchema;
        if (current != null && reported != null && !reported.isBlank() && reported.equals(compiledBundleDigest)) {
            return Maybe.just(current);
        }
        return Maybe.fromCallable(() -> compiledFrom(bundle)).subscribeOn(Schedulers.computation());
    }

    public CompiledXsdSchema compiledSchema() {
        if (initializationFailure != null && compiledSchema == null) {
            throw initializationFailure;
        }
        if (compiledSchema == null) {
            throw new IllegalStateException("XSD schema has not been initialized yet");
        }
        return compiledSchema;
    }

    /**
     * Used by registry compiler path to share Schema instances by digest across policy instances.
     */
    public static Schema schemaForDigest(String digest, java.util.function.Supplier<Schema> compiler) {
        return SCHEMA_BY_DIGEST.computeIfAbsent(digest, d -> compiler.get());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
