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
package io.gravitee.policy.xmlvalidation.xsd;

import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.xsd.XsdSource;
import io.gravitee.policy.xmlvalidation.configuration.xsd.XsdSourceType;

public final class XsdResolverFactory {

    private XsdResolverFactory() {}

    public static XsdResolver create(XmlValidationPolicyConfiguration configuration) {
        XsdSource xsdSource = configuration.getXsdSource();

        if (xsdSource == null) {
            return legacyXsdResolver(configuration);
        }

        return switch (xsdSource.getSourceType()) {
            case INLINE_XSD -> new StaticXsdResolver(xsdSource.getXsdSchema());
            case SCHEMA_REGISTRY_RESOURCE -> new ResourceBasedXsdResolver(xsdSource.getResourceName(), xsdSource.getSchemaMapping());
            case CONFLUENT_REST -> new ConfluentRestXsdResolver(xsdSource.getRegistryUrl(), xsdSource.getSchemaSubject());
        };
    }

    private static StaticXsdResolver legacyXsdResolver(XmlValidationPolicyConfiguration configuration) {
        return new StaticXsdResolver(configuration.getXsdSchema());
    }
}
