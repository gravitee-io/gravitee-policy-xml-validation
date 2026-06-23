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
package io.gravitee.policy.xmlvalidation.configuration.xsd;

public class XsdSource {

    private XsdSourceType sourceType;
    private String xsdSchema;
    private String resourceName;
    private String schemaMapping;

    /** Base URL of Confluent-compatible registry, e.g. http://localhost:8080/apis/ccompat/v7 */
    private String registryUrl;

    /** Subject name in the Confluent registry, e.g. order-schema */
    private String schemaSubject;

    public XsdSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(XsdSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getXsdSchema() {
        return xsdSchema;
    }

    public void setXsdSchema(String xsdSchema) {
        this.xsdSchema = xsdSchema;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getSchemaMapping() {
        return schemaMapping;
    }

    public void setSchemaMapping(String schemaMapping) {
        this.schemaMapping = schemaMapping;
    }

    public String getRegistryUrl() {
        return registryUrl;
    }

    public void setRegistryUrl(String registryUrl) {
        this.registryUrl = registryUrl;
    }

    public String getSchemaSubject() {
        return schemaSubject;
    }

    public void setSchemaSubject(String schemaSubject) {
        this.schemaSubject = schemaSubject;
    }
}
