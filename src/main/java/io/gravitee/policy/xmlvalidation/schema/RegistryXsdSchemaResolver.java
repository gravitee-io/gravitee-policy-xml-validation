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

import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.schema_registry.api.ArtifactSchemaLookup;
import io.gravitee.resource.schema_registry.api.SchemaArtifactNotFoundException;
import io.gravitee.resource.schema_registry.api.SchemaClosureLimitExceededException;
import io.gravitee.resource.schema_registry.api.SchemaLoadException;
import io.gravitee.resource.schema_registry.api.SchemaRegistryNotReadyException;
import io.gravitee.resource.schema_registry.api.SchemaRegistryUnreachableException;
import io.reactivex.rxjava3.core.Maybe;

public final class RegistryXsdSchemaResolver implements XsdSchemaResolver {

    private static final String SCHEMA_REGISTRY_RESOURCE_PREFIX = "Schema registry resource '";

    private final XmlValidationPolicyConfiguration configuration;
    private final ResourceManager resourceManager;
    private final CompiledXsdSchemaHolder holder;

    public RegistryXsdSchemaResolver(
        XmlValidationPolicyConfiguration configuration,
        ResourceManager resourceManager,
        CompiledXsdSchemaHolder holder
    ) {
        this.configuration = configuration;
        this.resourceManager = resourceManager;
        this.holder = holder;
    }

    @Override
    public Maybe<CompiledXsdSchema> resolveReactive() {
        return Maybe.defer(() -> {
            validateRequired(configuration.getRegistryResource(), "registryResource");
            validateRequired(configuration.getGroupId(), "groupId");
            validateRequired(configuration.getArtifactId(), "artifactId");
            validateRequired(configuration.getVersion(), "version");
            return fetchAndCompile();
        }).onErrorResumeNext(this::wrapAsResolutionFailure);
    }

    private Maybe<CompiledXsdSchema> wrapAsResolutionFailure(Throwable error) {
        if (error instanceof XsdSchemaResolutionException) {
            return Maybe.error(error);
        }
        // Unknown failures must not use CONFIGURATION: that kind is permanently pinned on the
        // policy instance. Keep CONFIGURATION for explicit misconfiguration only.
        return Maybe.error(
            new XsdSchemaResolutionException(
                "Unable to resolve XSD schema from the registry: " + error.getMessage(),
                SchemaSource.REGISTRY,
                XsdSchemaResolutionException.RegistryCoordinates.of(
                    configuration.getRegistryResource(),
                    configuration.getGroupId(),
                    configuration.getArtifactId(),
                    configuration.getVersion()
                ),
                error,
                XsdSchemaResolutionException.FailureKind.OTHER
            )
        );
    }

    private Maybe<CompiledXsdSchema> fetchAndCompile() {
        String registryResource = configuration.getRegistryResource();
        String groupId = configuration.getGroupId();
        String artifactId = configuration.getArtifactId();
        String version = configuration.getVersion();

        ArtifactSchemaLookup lookup;
        try {
            lookup = resourceManager.getResource(registryResource, ArtifactSchemaLookup.class);
        } catch (IllegalArgumentException typeMismatch) {
            return Maybe.error(
                new XsdSchemaResolutionException(
                    SCHEMA_REGISTRY_RESOURCE_PREFIX + registryResource + "' does not support artifact schema lookup",
                    SchemaSource.REGISTRY,
                    XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                    typeMismatch,
                    XsdSchemaResolutionException.FailureKind.CONFIGURATION
                )
            );
        }
        if (lookup == null) {
            return Maybe.error(
                new XsdSchemaResolutionException(
                    SCHEMA_REGISTRY_RESOURCE_PREFIX + registryResource + "' is not configured on the API",
                    SchemaSource.REGISTRY,
                    XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                    null,
                    XsdSchemaResolutionException.FailureKind.CONFIGURATION
                )
            );
        }
        if (!lookup.isReady()) {
            return Maybe.error(
                new XsdSchemaResolutionException(
                    SCHEMA_REGISTRY_RESOURCE_PREFIX + registryResource + "' is not ready",
                    SchemaSource.REGISTRY,
                    XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                    null,
                    XsdSchemaResolutionException.FailureKind.NOT_READY
                )
            );
        }

        return lookup
            .getArtifactSchema(groupId, artifactId, version, false)
            .switchIfEmpty(
                Maybe.error(
                    new XsdSchemaResolutionException(
                        "Schema artifact not found for groupId='" +
                            groupId +
                            "', artifactId='" +
                            artifactId +
                            "', version='" +
                            version +
                            "'",
                        SchemaSource.REGISTRY,
                        XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                        null,
                        XsdSchemaResolutionException.FailureKind.NOT_FOUND
                    )
                )
            )
            // Compile off the event loop only when the digest changed; reuse stays on the caller thread.
            .flatMap(bundle ->
                holder
                    .compiledFromReactive(bundle)
                    .onErrorResumeNext(error -> {
                        if (error instanceof XsdSchemaResolutionException) {
                            return Maybe.error(error);
                        }
                        return Maybe.error(
                            new XsdSchemaResolutionException(
                                "Unable to compile XSD schema: " + error.getMessage(),
                                SchemaSource.REGISTRY,
                                XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                                error,
                                XsdSchemaResolutionException.FailureKind.COMPILE
                            )
                        );
                    })
            )
            .onErrorResumeNext(error -> mapProviderFailure(error, registryResource, groupId, artifactId, version));
    }

    private Maybe<CompiledXsdSchema> mapProviderFailure(
        Throwable error,
        String registryResource,
        String groupId,
        String artifactId,
        String version
    ) {
        if (error instanceof XsdSchemaResolutionException) {
            return Maybe.error(error);
        }
        if (error instanceof SchemaArtifactNotFoundException) {
            return Maybe.error(
                new XsdSchemaResolutionException(
                    error.getMessage(),
                    SchemaSource.REGISTRY,
                    XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                    error,
                    XsdSchemaResolutionException.FailureKind.NOT_FOUND
                )
            );
        }
        if (error instanceof SchemaClosureLimitExceededException closureLimit) {
            return Maybe.error(
                new XsdSchemaResolutionException(
                    error.getMessage(),
                    SchemaSource.REGISTRY,
                    XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                    error,
                    XsdSchemaResolutionException.FailureKind.CLOSURE_LIMIT,
                    closureLimit.getLimitKind()
                )
            );
        }
        if (error instanceof SchemaRegistryNotReadyException) {
            return Maybe.error(
                new XsdSchemaResolutionException(
                    error.getMessage(),
                    SchemaSource.REGISTRY,
                    XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                    error,
                    XsdSchemaResolutionException.FailureKind.NOT_READY
                )
            );
        }
        if (error instanceof SchemaRegistryUnreachableException) {
            return Maybe.error(
                new XsdSchemaResolutionException(
                    error.getMessage(),
                    SchemaSource.REGISTRY,
                    XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                    error,
                    XsdSchemaResolutionException.FailureKind.UNREACHABLE
                )
            );
        }
        if (error instanceof SchemaLoadException) {
            // SchemaLoadException covers data-quality problems (empty content, malformed JSON,
            // parse errors) — not an unreachable registry. Map to OTHER so it produces a 400
            // (bad config / data) rather than a 503 (unreachable), and avoids triggering negative
            // caching in the resource.
            return Maybe.error(
                new XsdSchemaResolutionException(
                    error.getMessage(),
                    SchemaSource.REGISTRY,
                    XsdSchemaResolutionException.RegistryCoordinates.of(registryResource, groupId, artifactId, version),
                    error,
                    XsdSchemaResolutionException.FailureKind.OTHER
                )
            );
        }
        return Maybe.error(error);
    }

    private void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new XsdSchemaResolutionException(
                "Missing required registry configuration field '" + fieldName + "'",
                SchemaSource.REGISTRY,
                XsdSchemaResolutionException.RegistryCoordinates.of(
                    configuration.getRegistryResource(),
                    configuration.getGroupId(),
                    configuration.getArtifactId(),
                    configuration.getVersion()
                ),
                null,
                XsdSchemaResolutionException.FailureKind.CONFIGURATION
            );
        }
    }
}
