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

import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import lombok.Getter;

@Getter
public class XsdSchemaResolutionException extends RuntimeException {

    public enum FailureKind {
        NOT_FOUND,
        NOT_READY,
        UNREACHABLE,
        COMPILE,
        CLOSURE_LIMIT,
        /** Missing/wrong resource reference or incomplete policy configuration. */
        CONFIGURATION,
        OTHER,
    }

    /**
     * Registry coordinates carried on resolution failures for metrics / error parameters.
     */
    public record RegistryCoordinates(String registryResource, String groupId, String artifactId, String version) {
        public static RegistryCoordinates of(String registryResource, String groupId, String artifactId, String version) {
            return new RegistryCoordinates(registryResource, groupId, artifactId, version);
        }
    }

    private final SchemaSource schemaSource;
    private final String registryResource;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final FailureKind failureKind;

    public XsdSchemaResolutionException(String message, SchemaSource schemaSource, Throwable cause) {
        this(message, schemaSource, null, cause, FailureKind.OTHER);
    }

    public XsdSchemaResolutionException(String message, SchemaSource schemaSource) {
        this(message, schemaSource, null, null, FailureKind.OTHER);
    }

    public XsdSchemaResolutionException(String message, SchemaSource schemaSource, RegistryCoordinates coordinates, Throwable cause) {
        this(message, schemaSource, coordinates, cause, FailureKind.OTHER);
    }

    public XsdSchemaResolutionException(
        String message,
        SchemaSource schemaSource,
        RegistryCoordinates coordinates,
        Throwable cause,
        FailureKind failureKind
    ) {
        super(message, cause);
        this.schemaSource = schemaSource;
        if (coordinates == null) {
            this.registryResource = null;
            this.groupId = null;
            this.artifactId = null;
            this.version = null;
        } else {
            this.registryResource = coordinates.registryResource();
            this.groupId = coordinates.groupId();
            this.artifactId = coordinates.artifactId();
            this.version = coordinates.version();
        }
        this.failureKind = failureKind == null ? FailureKind.OTHER : failureKind;
    }
}
