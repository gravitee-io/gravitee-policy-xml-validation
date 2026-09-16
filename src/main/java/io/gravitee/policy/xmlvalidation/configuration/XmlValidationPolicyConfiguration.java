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
package io.gravitee.policy.xmlvalidation.configuration;

import io.gravitee.policy.api.PolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;

public class XmlValidationPolicyConfiguration implements PolicyConfiguration {

    private String errorMessage;

    private SchemaSource schemaSource;

    private String xsdSchema;

    private String registryResource;

    private String groupId;

    private String artifactId;

    private String version;

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Returns {@link SchemaSource#INLINE} when {@code schemaSource} is absent (legacy configs).
     */
    public SchemaSource getSchemaSource() {
        return schemaSource != null ? schemaSource : SchemaSource.INLINE;
    }

    public void setSchemaSource(SchemaSource schemaSource) {
        this.schemaSource = schemaSource;
    }

    public String getXsdSchema() {
        return xsdSchema;
    }

    public void setXsdSchema(String xsdSchema) {
        this.xsdSchema = xsdSchema;
    }

    public String getRegistryResource() {
        return registryResource;
    }

    public void setRegistryResource(String registryResource) {
        this.registryResource = registryResource;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
