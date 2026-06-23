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
package io.gravitee.policy.xmlvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.util.ServiceLoaderHelper;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.buffer.BufferFactory;
import io.gravitee.gateway.api.stream.ReadWriteStream;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.xsd.XsdSource;
import io.gravitee.policy.xmlvalidation.configuration.xsd.XsdSourceType;
import io.gravitee.reporter.api.http.Metrics;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.schema_registry.api.Schema;
import io.gravitee.resource.schema_registry.api.SchemaRegistryResource;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.xml.sax.SAXException;

@RunWith(MockitoJUnitRunner.class)
public class XmlValidationTest {

    @Mock
    private Request mockRequest;

    @Mock
    private Response mockResponse;

    @Mock
    private ExecutionContext mockExecutionContext;

    @Mock
    private PolicyChain mockPolicychain;

    private final BufferFactory factory = ServiceLoaderHelper.loadFactory(BufferFactory.class);

    private final String xsdSchema =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" attributeFormDefault=\"unqualified\"\n" +
        "           elementFormDefault=\"qualified\">\n" +
        "    <xs:element name=\"root\" type=\"rootType\">\n" +
        "    </xs:element>\n" +
        "\n" +
        "    <xs:complexType name=\"rootType\">\n" +
        "        <xs:sequence>\n" +
        "            <xs:element name=\"companies\" type=\"companiesType\"/>\n" +
        "        </xs:sequence>\n" +
        "    </xs:complexType>\n" +
        "\n" +
        "    <xs:complexType name=\"companiesType\">\n" +
        "        <xs:sequence>\n" +
        "            <xs:element name=\"company\" type=\"companyType\" maxOccurs=\"unbounded\" minOccurs=\"0\"/>\n" +
        "        </xs:sequence>\n" +
        "    </xs:complexType>\n" +
        "\n" +
        "    <xs:complexType name=\"companyType\">\n" +
        "        <xs:sequence>\n" +
        "            <xs:element type=\"xs:string\" name=\"name\"/>\n" +
        "            <xs:element type=\"xs:integer\" name=\"employeeNumber\"/>\n" +
        "            <xs:element type=\"xs:long\" name=\"sales\"/>\n" +
        "            <xs:element type=\"xs:string\" name=\"CEO\"/>\n" +
        "        </xs:sequence>\n" +
        "    </xs:complexType>\n" +
        "</xs:schema>";

    private final Buffer validXmlContent = factory.buffer(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<root>\n" +
        "    <companies>\n" +
        "        <company>\n" +
        "            <name>Foo Inc</name>\n" +
        "            <employeeNumber>752</employeeNumber>\n" +
        "            <sales>10451541505</sales>\n" +
        "            <CEO>John Doo</CEO>\n" +
        "        </company>\n" +
        "    </companies>\n" +
        "</root>"
    );

    private final Buffer invalidXmContent = factory.buffer(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<root>\n" +
        "        <company>\n" +
        "            <name>Foo Inc</name>\n" +
        "            <employeeNumber>752</employeeNumber>\n" +
        "            <sales>10451541505</sales>\n" +
        "            <CEO>John Doo</CEO>\n" +
        "        </company>\n" +
        "</root>"
    );

    private Metrics metrics;

    private XmlValidationPolicy policy;

    @Before
    public void beforeAll() throws SAXException, IOException {
        metrics = Metrics.on(System.currentTimeMillis()).build();

        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setErrorMessage(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "  <error>\n" +
            "    <reason>validation/internal</reason>\n" +
            "    <internalReason>Internal error occurred. Please retry...</internalReason>\n" +
            "  </error>"
        );
        configuration.setXsdSchema(xsdSchema);

        when(mockRequest.metrics()).thenReturn(metrics);
        when(mockExecutionContext.getTemplateEngine()).thenReturn(TemplateEngine.templateEngine());

        policy = new XmlValidationPolicy(configuration);
    }

    @Test
    public void shouldAcceptValidPayload() {
        ReadWriteStream readWriteStream = policy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(validXmlContent);
        readWriteStream.end();
        verify(mockPolicychain, times(0)).streamFailWith(ArgumentMatchers.isA(PolicyResult.class));
    }

    @Test
    public void shouldValidateRejectInvalidPayload() {
        ReadWriteStream readWriteStream = policy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(invalidXmContent);
        readWriteStream.end();

        policyAssertions(HttpStatusCode.BAD_REQUEST_400);
    }

    @Test
    public void shouldMalformedPayloadBeRejected() {
        Buffer buffer = factory.buffer("{\"name\"");
        ReadWriteStream readWriteStream = policy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(buffer);
        readWriteStream.end();

        policyAssertions(HttpStatusCode.BAD_REQUEST_400);
    }

    @Test
    public void shouldRejectMalformedXsdSchemaAtInitialization() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setXsdSchema("\"msg\":\"error\"}");

        assertThatThrownBy(() -> new XmlValidationPolicy(configuration)).isInstanceOf(SAXException.class);
    }

    // --- Registry-based XSD source tests ---

    @Test
    public void shouldAcceptValidPayloadFromSchemaRegistryResource() throws SAXException, IOException {
        ResourceManager resourceManager = mock(ResourceManager.class);
        SchemaRegistryResource<?> schemaRegistryResource = mock(SchemaRegistryResource.class);
        Schema registrySchema = mock(Schema.class);

        when(mockExecutionContext.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(resourceManager.getResource("embedded-registry", SchemaRegistryResource.class)).thenReturn(schemaRegistryResource);
        when(schemaRegistryResource.getSchema("order-schema")).thenReturn(Maybe.just(registrySchema));
        when(registrySchema.getContent()).thenReturn(xsdSchema);

        XsdSource xsdSource = new XsdSource();
        xsdSource.setSourceType(XsdSourceType.SCHEMA_REGISTRY_RESOURCE);
        xsdSource.setResourceName("embedded-registry");
        xsdSource.setSchemaMapping("order-schema");

        XmlValidationPolicyConfiguration config = new XmlValidationPolicyConfiguration();
        config.setXsdSource(xsdSource);

        XmlValidationPolicy registryPolicy = new XmlValidationPolicy(config);

        ReadWriteStream stream = registryPolicy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        stream.write(validXmlContent);
        stream.end();

        verify(mockPolicychain, times(0)).streamFailWith(any(PolicyResult.class));
    }

    @Test
    public void shouldReturn500WhenSchemaRegistryResourceNotFound() throws SAXException, IOException {
        ResourceManager resourceManager = mock(ResourceManager.class);
        when(mockExecutionContext.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        // getResource returns null by default — resource not configured on this API

        XsdSource xsdSource = new XsdSource();
        xsdSource.setSourceType(XsdSourceType.SCHEMA_REGISTRY_RESOURCE);
        xsdSource.setResourceName("missing-registry");
        xsdSource.setSchemaMapping("order-schema");

        XmlValidationPolicyConfiguration config = new XmlValidationPolicyConfiguration();
        config.setXsdSource(xsdSource);

        XmlValidationPolicy registryPolicy = new XmlValidationPolicy(config);

        ReadWriteStream stream = registryPolicy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        stream.write(validXmlContent);
        stream.end();

        policyAssertions(HttpStatusCode.INTERNAL_SERVER_ERROR_500);
    }

    @Test
    public void shouldReturn400WhenRegistryReturnsSchemaButPayloadIsInvalid() throws SAXException, IOException {
        ResourceManager resourceManager = mock(ResourceManager.class);
        SchemaRegistryResource<?> schemaRegistryResource = mock(SchemaRegistryResource.class);
        Schema registrySchema = mock(Schema.class);

        when(mockExecutionContext.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(resourceManager.getResource("embedded-registry", SchemaRegistryResource.class)).thenReturn(schemaRegistryResource);
        when(schemaRegistryResource.getSchema("order-schema")).thenReturn(Maybe.just(registrySchema));
        when(registrySchema.getContent()).thenReturn(xsdSchema);

        XsdSource xsdSource = new XsdSource();
        xsdSource.setSourceType(XsdSourceType.SCHEMA_REGISTRY_RESOURCE);
        xsdSource.setResourceName("embedded-registry");
        xsdSource.setSchemaMapping("order-schema");

        XmlValidationPolicyConfiguration config = new XmlValidationPolicyConfiguration();
        config.setXsdSource(xsdSource);

        XmlValidationPolicy registryPolicy = new XmlValidationPolicy(config);

        ReadWriteStream stream = registryPolicy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        stream.write(invalidXmContent);
        stream.end();

        policyAssertions(HttpStatusCode.BAD_REQUEST_400);
    }

    // --- Error key / message assertions ---

    @Test
    public void shouldReturn400WithValidationErrorKey() throws SAXException, IOException {
        XmlValidationPolicyConfiguration config = new XmlValidationPolicyConfiguration();
        config.setXsdSchema(xsdSchema);
        XmlValidationPolicy noTemplatePolicy = new XmlValidationPolicy(config);

        ReadWriteStream readWriteStream = noTemplatePolicy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(invalidXmContent);
        readWriteStream.end();

        // SAXException message contains the XSD constraint detail, e.g. "Invalid content was found..."
        policyAssertions(HttpStatusCode.BAD_REQUEST_400, XmlValidationPolicy.ERROR_KEY_VALIDATION, "Invalid content");
    }

    @Test
    public void shouldReturn400WithValidationErrorKeyForMalformedXml() throws SAXException, IOException {
        XmlValidationPolicyConfiguration config = new XmlValidationPolicyConfiguration();
        config.setXsdSchema(xsdSchema);
        XmlValidationPolicy noTemplatePolicy = new XmlValidationPolicy(config);

        Buffer buffer = factory.buffer("{\"name\"");
        ReadWriteStream readWriteStream = noTemplatePolicy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(buffer);
        readWriteStream.end();

        // SAXException message for non-XML input, e.g. "Content is not allowed in prolog."
        policyAssertions(HttpStatusCode.BAD_REQUEST_400, XmlValidationPolicy.ERROR_KEY_VALIDATION, "Content is not allowed");
    }

    @Test
    public void shouldReturn500WithRegistryErrorKey() throws SAXException, IOException {
        ResourceManager resourceManager = mock(ResourceManager.class);
        when(mockExecutionContext.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        // getResource returns null → registry lookup fails

        XsdSource xsdSource = new XsdSource();
        xsdSource.setSourceType(XsdSourceType.SCHEMA_REGISTRY_RESOURCE);
        xsdSource.setResourceName("missing-registry");
        xsdSource.setSchemaMapping("order-schema");

        XmlValidationPolicyConfiguration config = new XmlValidationPolicyConfiguration();
        config.setXsdSource(xsdSource);

        XmlValidationPolicy registryPolicy = new XmlValidationPolicy(config);

        ReadWriteStream stream = registryPolicy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        stream.write(validXmlContent);
        stream.end();

        policyAssertions(HttpStatusCode.INTERNAL_SERVER_ERROR_500, XmlValidationPolicy.ERROR_KEY_SCHEMA_REGISTRY, "Unable to resolve schema registry resource:");
    }

    private void policyAssertions(int expectedStatusCode) {
        assertThat(metrics.getMessage()).isNotEmpty();
        ArgumentCaptor<PolicyResult> policyResult = ArgumentCaptor.forClass(PolicyResult.class);
        verify(mockPolicychain, times(1)).streamFailWith(policyResult.capture());
        PolicyResult value = policyResult.getValue();
        assertThat(value.statusCode()).isEqualTo(expectedStatusCode);
    }

    private void policyAssertions(int expectedStatusCode, String expectedKey, String expectedMessagePrefix) {
        assertThat(metrics.getMessage()).isNotEmpty();
        ArgumentCaptor<PolicyResult> policyResult = ArgumentCaptor.forClass(PolicyResult.class);
        verify(mockPolicychain, times(1)).streamFailWith(policyResult.capture());
        PolicyResult value = policyResult.getValue();
        assertThat(value.statusCode()).isEqualTo(expectedStatusCode);
        assertThat(value.key()).isEqualTo(expectedKey);
        assertThat(value.message()).contains(expectedMessagePrefix);
    }
}
