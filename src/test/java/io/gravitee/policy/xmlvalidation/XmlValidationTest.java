/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.xmlvalidation;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.gravitee.reporter.api.http.Metrics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

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

    @Mock
    private XmlValidationPolicyConfiguration configuration;

    private BufferFactory factory = ServiceLoaderHelper.loadFactory(BufferFactory.class);

    private String xsdSchema =
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

    private Buffer validXmlContent = factory.buffer(
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

    private Buffer invalidXmContent = factory.buffer(
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
    public void beforeAll() {
        metrics = Metrics.on(System.currentTimeMillis()).build();

        when(configuration.getErrorMessage())
            .thenReturn(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "  <error>\n" +
                "    <reason>validation/internal</reason>\n" +
                "    <internalReason>Internal error occurred. Please retry...</internalReason>\n" +
                "  </error>"
            );
        when(configuration.getXsdSchema()).thenReturn(xsdSchema);
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

        policyAssertions();
    }

    @Test
    public void shouldMalformedPayloadBeRejected() {
        Buffer buffer = factory.buffer("{\"name\"");
        ReadWriteStream readWriteStream = policy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(buffer);
        readWriteStream.end();

        policyAssertions();
    }

    @Test
    public void shouldMalformedXsdSchemaBeRejected() {
        when(configuration.getXsdSchema()).thenReturn("\"msg\":\"error\"}");

        ReadWriteStream readWriteStream = policy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(validXmlContent);
        readWriteStream.end();

        policyAssertions();
    }

    private void policyAssertions() {
        assertThat(metrics.getMessage()).isNotEmpty();
        ArgumentCaptor<PolicyResult> policyResult = ArgumentCaptor.forClass(PolicyResult.class);
        verify(mockPolicychain, times(1)).streamFailWith(policyResult.capture());
        PolicyResult value = policyResult.getValue();
        assertThat(value.message()).isEqualTo(configuration.getErrorMessage());
        assertThat(value.statusCode() == HttpStatusCode.BAD_REQUEST_400);
    }
}
