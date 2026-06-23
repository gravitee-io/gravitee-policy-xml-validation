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
import io.gravitee.policy.xmlvalidation.configuration.xsd.XsdSource;

public class XmlValidationPolicyConfiguration implements PolicyConfiguration {

    private String errorMessage;

    /**
     * @deprecated use {@link #xsdSource} instead.
     */
    @Deprecated
    private String xsdSchema;

    private XsdSource xsdSource;

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Deprecated
    public String getXsdSchema() {
        return xsdSchema;
    }

    @Deprecated
    public void setXsdSchema(String xsdSchema) {
        this.xsdSchema = xsdSchema;
    }

    public XsdSource getXsdSource() {
        return xsdSource;
    }

    public void setXsdSource(XsdSource xsdSource) {
        this.xsdSource = xsdSource;
    }
}
