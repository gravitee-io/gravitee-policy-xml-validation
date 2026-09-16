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
import static org.mockito.Mockito.*;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.util.ServiceLoaderHelper;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.buffer.BufferFactory;
import io.gravitee.gateway.api.stream.ReadWriteStream;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.v3.xmlvalidation.XmlValidationPolicyV3;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.reporter.api.http.Metrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class XmlValidationTest {

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
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" attributeFormDefault="unqualified"
                   elementFormDefault="qualified">
            <xs:element name="root" type="rootType">
            </xs:element>

            <xs:complexType name="rootType">
                <xs:sequence>
                    <xs:element name="companies" type="companiesType"/>
                </xs:sequence>
            </xs:complexType>

            <xs:complexType name="companiesType">
                <xs:sequence>
                    <xs:element name="company" type="companyType" maxOccurs="unbounded" minOccurs="0"/>
                </xs:sequence>
            </xs:complexType>

            <xs:complexType name="companyType">
                <xs:sequence>
                    <xs:element type="xs:string" name="name"/>
                    <xs:element type="xs:integer" name="employeeNumber"/>
                    <xs:element type="xs:long" name="sales"/>
                    <xs:element type="xs:string" name="CEO"/>
                </xs:sequence>
            </xs:complexType>
        </xs:schema>""";

    private final Buffer validXmlContent = factory.buffer(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <root>
            <companies>
                <company>
                    <name>Foo Inc</name>
                    <employeeNumber>752</employeeNumber>
                    <sales>10451541505</sales>
                    <CEO>John Doo</CEO>
                </company>
            </companies>
        </root>"""
    );

    private final Buffer invalidXmlContent = factory.buffer(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <root>
                <company>
                    <name>Foo Inc</name>
                    <employeeNumber>752</employeeNumber>
                    <sales>10451541505</sales>
                    <CEO>John Doo</CEO>
                </company>
        </root>"""
    );

    private Metrics metrics;
    private XmlValidationPolicyConfiguration configuration;
    private XmlValidationPolicyV3 policy;

    @BeforeEach
    void setUp() {
        metrics = Metrics.on(System.currentTimeMillis()).build();
        configuration = new XmlValidationPolicyConfiguration();
        configuration.setSchemaSource(SchemaSource.INLINE);
        configuration.setXsdSchema(xsdSchema);

        policy = new XmlValidationPolicyV3(configuration);
    }

    private void stubRequestMetrics() {
        when(mockRequest.metrics()).thenReturn(metrics);
    }

    @Test
    void shouldAcceptValidPayload() {
        ReadWriteStream<Buffer> readWriteStream = policy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(validXmlContent);
        readWriteStream.end();
        verify(mockPolicychain, times(0)).streamFailWith(any(PolicyResult.class));
    }

    @Test
    void shouldRejectInvalidPayloadWithDetailedMessage() {
        stubRequestMetrics();
        ReadWriteStream<Buffer> readWriteStream = policy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(invalidXmlContent);
        readWriteStream.end();

        assertThat(metrics.getMessage()).contains("company");
        ArgumentCaptor<PolicyResult> policyResult = ArgumentCaptor.forClass(PolicyResult.class);
        verify(mockPolicychain, times(1)).streamFailWith(policyResult.capture());
        PolicyResult value = policyResult.getValue();
        assertThat(value.message()).contains("company");
        assertThat(value.statusCode()).isEqualTo(HttpStatusCode.BAD_REQUEST_400);
    }

    @Test
    void shouldAlwaysReturnValidationMessageInV3() {
        stubRequestMetrics();

        ReadWriteStream<Buffer> readWriteStream = policy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(invalidXmlContent);
        readWriteStream.end();

        ArgumentCaptor<PolicyResult> policyResult = ArgumentCaptor.forClass(PolicyResult.class);
        verify(mockPolicychain, times(1)).streamFailWith(policyResult.capture());
        assertThat(policyResult.getValue().message()).contains("company");
    }

    @Test
    void shouldRejectMalformedPayload() {
        stubRequestMetrics();
        Buffer buffer = factory.buffer("{\"name\"");
        ReadWriteStream<Buffer> readWriteStream = policy.onRequestContent(mockRequest, mockResponse, mockExecutionContext, mockPolicychain);
        readWriteStream.write(buffer);
        readWriteStream.end();

        verify(mockPolicychain, times(1)).streamFailWith(any(PolicyResult.class));
    }
}
