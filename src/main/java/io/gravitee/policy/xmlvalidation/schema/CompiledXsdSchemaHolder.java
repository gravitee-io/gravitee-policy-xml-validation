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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.validation.Schema;

/**
 * Holds a compiled XSD for a policy instance. Registry schemas use a static digest→Schema cache
 * scoped to the policy classloader (API lifetime) so chain eviction does not force recompilation.
 */
public final class CompiledXsdSchemaHolder {

    /**
     * API-classloader-scoped: each API's policy ClassLoader has its own static map.
     */
    private static final ConcurrentHashMap<String, Schema> SCHEMA_BY_DIGEST = new ConcurrentHashMap<>();

    private final XmlValidationPolicyConfiguration configuration;
    private final Object lock = new Object();
    private volatile CompiledXsdSchema compiledSchema;
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
        if (compiledSchema != null) {
            return Completable.complete();
        }
        if (initializationFailure != null) {
            return Completable.error(initializationFailure);
        }
        if (configuration.getSchemaSource() == SchemaSource.INLINE) {
            return Completable.complete();
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
            if (compiledSchema != null) {
                return Maybe.just(compiledSchema);
            }
            if (initializationFailure != null) {
                return Maybe.error(initializationFailure);
            }
            XsdSchemaResolver resolver = XsdSchemaResolverFactory.create(configuration, resourceManager);
            cached =
                resolver
                    .resolveReactive()
                    .map(this::internByDigest)
                    .doOnSuccess(schema -> {
                        synchronized (lock) {
                            if (compiledSchema == null) {
                                compiledSchema = schema;
                            }
                        }
                    })
                    .doOnError(this::recordFailure)
                    .cache();
            inFlight = cached;
            return cached;
        }
    }

    private CompiledXsdSchema internByDigest(CompiledXsdSchema compiled) {
        // Registry path already compiled; digest interning happens inside compiler via bundle.digest when available.
        // For resolver results we keep the instance; Schema object identity is fine within classloader cache when keyed.
        return compiled;
    }

    private void recordFailure(Throwable error) {
        XsdSchemaResolutionException ex;
        if (error instanceof XsdSchemaResolutionException xsre) {
            ex = xsre;
        } else {
            ex =
                new XsdSchemaResolutionException(
                    "Schema resolution failed: " + error.getMessage(),
                    SchemaSource.REGISTRY,
                    configuration.getRegistryResource(),
                    configuration.getGroupId(),
                    configuration.getArtifactId(),
                    configuration.getVersion(),
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
