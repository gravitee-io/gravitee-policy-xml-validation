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
package io.gravitee.policy.xmlvalidation.swagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.policy.api.swagger.Policy;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.HashMap;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Florent CHAMFROY (florent.chamfroy at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class XmlValidationOAIOperationVisitorTest {

    private XmlValidationOAIOperationVisitor visitor = new XmlValidationOAIOperationVisitor();

    @Test
    public void operationWithoutRequestBody() {
        Operation operationMock = mock(Operation.class);

        when(operationMock.getRequestBody()).thenReturn(null);
        Optional<Policy> policy = visitor.visit(mock(OpenAPI.class), operationMock);
        assertFalse(policy.isPresent());
    }

    @Test
    public void operationWithoutApplicationJsonRequestBody() {
        Operation operationMock = mock(Operation.class);

        Content content = mock(Content.class);
        RequestBody requestBody = mock(RequestBody.class);
        when(operationMock.getRequestBody()).thenReturn(requestBody);
        when(requestBody.getContent()).thenReturn(content);
        when(content.get("application/xml")).thenReturn(null);

        Optional<Policy> policy = visitor.visit(mock(OpenAPI.class), operationMock);
        assertFalse(policy.isPresent());
    }

    @Test
    public void operationWithEmptyRequestBody() {
        Operation operationMock = mock(Operation.class);

        MediaType applicationXml = mock(MediaType.class);
        Content content = mock(Content.class);
        RequestBody requestBody = mock(RequestBody.class);
        when(operationMock.getRequestBody()).thenReturn(requestBody);
        when(requestBody.getContent()).thenReturn(content);
        when(content.get("application/xml")).thenReturn(applicationXml);

        try(MockedStatic<Json> theMock = Mockito.mockStatic(Json.class)) {
            theMock.when(() -> Json.pretty(any(Schema.class))).thenReturn("");
            Optional<Policy> policy = visitor.visit(mock(OpenAPI.class), operationMock);
            assertFalse(policy.isPresent());
        }
    }

    @Test
    public void operationWithJsonRequestBody() throws Exception {
        final String jsonSchema = "{\n" +
                "  \"title\": \"Person\",\n" +
                "  \"type\": \"object\",\n" +
                "  \"properties\": {\n" +
                "    \"firstName\": {\n" +
                "      \"type\": \"string\",\n" +
                "      \"description\": \"The person's first name.\"\n" +
                "    },\n" +
                "    \"lastName\": {\n" +
                "      \"type\": \"string\",\n" +
                "      \"description\": \"The person's last name.\"\n" +
                "    },\n" +
                "    \"age\": {\n" +
                "      \"description\": \"Age in years which must be equal to or greater than zero.\",\n" +
                "      \"type\": \"integer\",\n" +
                "      \"format\": \"int64\",\n" +
                "      \"minimum\": 0\n" +
                "    }\n" +
                "  }\n" +
                "}";

        final String expectedXsdSchema = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><schema xmlns=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" targetNamespace=\"" + XmlValidationOAIOperationVisitor.XSD_TARGET_NAMESPACE + "\" xmlns:ns=\"" + XmlValidationOAIOperationVisitor.XSD_TARGET_NAMESPACE + "\">\n" +
                "    <element name=\"root\" type=\"ns:rootType\"/>\n" +
                "    <complexType name=\"rootType\">\n" +
                "        <sequence>\n" +
                "            <element minOccurs=\"0\" name=\"firstName\" type=\"string\">\n" +
                "                <annotation>\n" +
                "                    <documentation>The person's first name.</documentation>\n" +
                "                </annotation>\n" +
                "            </element>\n" +
                "            <element minOccurs=\"0\" name=\"lastName\" type=\"string\">\n" +
                "                <annotation>\n" +
                "                    <documentation>The person's last name.</documentation>\n" +
                "                </annotation>\n" +
                "            </element>\n" +
                "            <element minOccurs=\"0\" name=\"age\" type=\"long\">\n" +
                "                <annotation>\n" +
                "                    <documentation>Age in years which must be equal to or greater than zero.</documentation>\n" +
                "                </annotation>\n" +
                "            </element>\n" +
                "        </sequence>\n" +
                "    </complexType>\n" +
                "</schema>\n";

        Operation operationMock = mock(Operation.class);

        Schema schema = mock(Schema.class);
        MediaType applicationXml = mock(MediaType.class);
        Content content = mock(Content.class);
        RequestBody requestBody = mock(RequestBody.class);
        when(operationMock.getRequestBody()).thenReturn(requestBody);
        when(requestBody.getContent()).thenReturn(content);
        when(content.get("application/xml")).thenReturn(applicationXml);
        when(applicationXml.getSchema()).thenReturn(schema);

        try(MockedStatic<Json> theMock = Mockito.mockStatic(Json.class)) {
            theMock.when(() -> Json.pretty(any(Schema.class))).thenReturn(jsonSchema);

            Optional<Policy> policy = visitor.visit(mock(OpenAPI.class), operationMock);
            assertTrue(policy.isPresent());

            String configuration = policy.get().getConfiguration();
            assertNotNull(configuration);
            HashMap readConfig = new ObjectMapper().readValue(configuration, HashMap.class);
            assertEquals(expectedXsdSchema.replaceAll("\\s", ""), ((String) readConfig.get("xsdSchema")).replaceAll("\\s", ""));
        }
    }
}
