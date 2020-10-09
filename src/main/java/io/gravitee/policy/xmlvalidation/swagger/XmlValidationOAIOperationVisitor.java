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

import com.ethlo.jsons2xsd.Config;
import com.ethlo.jsons2xsd.JsonSimpleType;
import com.ethlo.jsons2xsd.Jsons2Xsd;
import com.ethlo.jsons2xsd.XsdSimpleType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.gravitee.policy.api.swagger.Policy;
import io.gravitee.policy.api.swagger.v3.OAIOperationVisitor;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.DOMConfiguration;
import org.w3c.dom.Document;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Optional;

/**
 * @author Florent CHAMFROY (florent.chamfroy at graviteesource.com)
 * @author GraviteeSource Team
 */
public class XmlValidationOAIOperationVisitor implements OAIOperationVisitor {

    public static final String XSD_TARGET_NAMESPACE = "urn:io:gravitee:policy:xml-validation";

    private final ObjectMapper mapper = new ObjectMapper();
    {
        mapper.configure(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS, true);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    /**
     * openAPI has been parsed with the "resolveFully" option. As a consequence, all $ref have been replaced by proper definition.
     */
    public Optional<Policy> visit(io.swagger.v3.oas.models.OpenAPI openAPI, io.swagger.v3.oas.models.Operation operation) {
        String jsonSchema = null;
        final RequestBody requestBody = operation.getRequestBody();
        if (requestBody != null && requestBody.getContent() != null && requestBody.getContent().get("application/xml") != null) {
            final Schema schema = requestBody.getContent().get("application/xml").getSchema();
            jsonSchema = Json.pretty(schema);
        }
        if (!StringUtils.isEmpty(jsonSchema)) {
            XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
            try {
                String xmlSchema = convert(jsonSchema);

                Policy policy = new Policy();
                policy.setName("xml-validation");
                configuration.setXsdSchema(xmlSchema);
                policy.setConfiguration(mapper.writeValueAsString(configuration));
                return Optional.of(policy);
            } catch (IOException | IllegalAccessException | InstantiationException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        return Optional.empty();
    }

    private String convert(String jsonSchema) throws IOException, InstantiationException, IllegalAccessException, ClassNotFoundException {
        try(Reader jsonReader = new StringReader(jsonSchema)) {
            final Config cfg = new Config.Builder()
                    .name("rootType")
                    .targetNamespace(XSD_TARGET_NAMESPACE)
                    .createRootElement(true)
                    .nsAlias("ns")
                    .rootElement("root")
                    .validateXsdSchema(false)
                    .customTypeMapping(JsonSimpleType.INTEGER, "int64", XsdSimpleType.LONG)
                    .build();
            final Document xmlSchemaDoc = Jsons2Xsd.convert(jsonReader, cfg);

            DOMImplementationLS impl = (DOMImplementationLS) DOMImplementationRegistry.newInstance().getDOMImplementation("LS");

            LSSerializer writer = impl.createLSSerializer();
            DOMConfiguration config = writer.getDomConfig();
            config.setParameter("format-pretty-print", Boolean.TRUE);

            return writer.writeToString(xmlSchemaDoc);
        }
    }
}
