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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.xsd.XsdSource;
import io.gravitee.policy.xmlvalidation.configuration.xsd.XsdSourceType;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.schema_registry.api.Schema;
import io.gravitee.resource.schema_registry.api.SchemaRegistryResource;
import io.reactivex.rxjava3.core.Maybe;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class XsdResolverTest {

    private static final String XSD_SCHEMA =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" elementFormDefault="qualified">
          <xs:element name="root" type="xs:string"/>
        </xs:schema>
        """;

    @Mock
    private ExecutionContext executionContext;

    @Mock
    private ResourceManager resourceManager;

    @Mock
    private SchemaRegistryResource<?> schemaRegistryResource;

    @Test
    public void shouldResolveLegacyInlineXsd() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setXsdSchema(XSD_SCHEMA);

        XsdResolver resolver = XsdResolverFactory.create(configuration);

        assertThat(resolver.resolveXsd(executionContext)).isEqualTo(XSD_SCHEMA);
    }

    @Test
    public void shouldResolveInlineXsdFromSource() {
        XsdSource xsdSource = new XsdSource();
        xsdSource.setSourceType(XsdSourceType.INLINE_XSD);
        xsdSource.setXsdSchema(XSD_SCHEMA);

        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setXsdSource(xsdSource);

        XsdResolver resolver = XsdResolverFactory.create(configuration);

        assertThat(resolver.resolveXsd(executionContext)).isEqualTo(XSD_SCHEMA);
    }

    @Test
    public void shouldResolveXsdFromSchemaRegistryResourceBySubject() {
        when(executionContext.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(resourceManager.getResource("embedded-registry", SchemaRegistryResource.class)).thenReturn(schemaRegistryResource);
        when(executionContext.getTemplateEngine()).thenReturn(TemplateEngine.templateEngine());

        Schema schema = mock(Schema.class);
        when(schema.getContent()).thenReturn(XSD_SCHEMA);
        when(schemaRegistryResource.getSchemaById("order-request-v1")).thenReturn(Maybe.empty());
        when(schemaRegistryResource.getSchema("order-request-v1")).thenReturn(Maybe.just(schema));

        XsdSource xsdSource = new XsdSource();
        xsdSource.setSourceType(XsdSourceType.SCHEMA_REGISTRY_RESOURCE);
        xsdSource.setResourceName("embedded-registry");
        xsdSource.setSchemaMapping("order-request-v1");

        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setXsdSource(xsdSource);

        XsdResolver resolver = XsdResolverFactory.create(configuration);

        assertThat(resolver.resolveXsd(executionContext)).isEqualTo(XSD_SCHEMA);
    }

    @Test
    public void shouldResolveXsdFromSchemaRegistryResourceById() {
        when(executionContext.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(resourceManager.getResource("my-xsd-registry", SchemaRegistryResource.class)).thenReturn(schemaRegistryResource);
        when(executionContext.getTemplateEngine()).thenReturn(TemplateEngine.templateEngine());

        Schema schema = mock(Schema.class);
        when(schema.getContent()).thenReturn(XSD_SCHEMA);
        when(schemaRegistryResource.getSchemaById("order-schema")).thenReturn(Maybe.just(schema));

        XsdSource xsdSource = new XsdSource();
        xsdSource.setSourceType(XsdSourceType.SCHEMA_REGISTRY_RESOURCE);
        xsdSource.setResourceName("my-xsd-registry");
        xsdSource.setSchemaMapping("order-schema");

        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setXsdSource(xsdSource);

        XsdResolver resolver = XsdResolverFactory.create(configuration);

        assertThat(resolver.resolveXsd(executionContext)).isEqualTo(XSD_SCHEMA);
    }
}
