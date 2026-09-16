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

    private final SchemaSource schemaSource;
    private final String registryResource;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final FailureKind failureKind;

    public XsdSchemaResolutionException(String message, SchemaSource schemaSource, Throwable cause) {
        this(message, schemaSource, null, null, null, null, cause, FailureKind.OTHER);
    }

    public XsdSchemaResolutionException(String message, SchemaSource schemaSource) {
        this(message, schemaSource, null, null, null, null, null, FailureKind.OTHER);
    }

    public XsdSchemaResolutionException(
        String message,
        SchemaSource schemaSource,
        String registryResource,
        String groupId,
        String artifactId,
        String version,
        Throwable cause
    ) {
        this(message, schemaSource, registryResource, groupId, artifactId, version, cause, FailureKind.OTHER);
    }

    public XsdSchemaResolutionException(
        String message,
        SchemaSource schemaSource,
        String registryResource,
        String groupId,
        String artifactId,
        String version,
        Throwable cause,
        FailureKind failureKind
    ) {
        super(message, cause);
        this.schemaSource = schemaSource;
        this.registryResource = registryResource;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.failureKind = failureKind == null ? FailureKind.OTHER : failureKind;
    }
}
