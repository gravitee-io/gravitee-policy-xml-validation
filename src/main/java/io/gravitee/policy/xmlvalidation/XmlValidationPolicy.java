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

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.stream.BufferedReadWriteStream;
import io.gravitee.gateway.api.stream.ReadWriteStream;
import io.gravitee.gateway.api.stream.SimpleReadWriteStream;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequestContent;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

public class XmlValidationPolicy {

    private static final Logger logger = LoggerFactory.getLogger(XmlValidationPolicy.class);

    private static final String BAD_REQUEST = "Bad Request";
    private static final String INTERNAL_ERROR = "Internal Error";

    private XmlValidationPolicyConfiguration configuration;

    public XmlValidationPolicy(XmlValidationPolicyConfiguration jsonSchemaValidatorPolicyConfiguration) {
        this.configuration = jsonSchemaValidatorPolicyConfiguration;
    }

    @OnRequestContent
    public ReadWriteStream onRequestContent(
        Request request,
        Response response,
        ExecutionContext executionContext,
        PolicyChain policyChain
    ) {
        logger.debug("Execute XML validation policy on request {}", request.id());
        return new BufferedReadWriteStream() {
            Buffer buffer = Buffer.buffer();

            @Override
            public SimpleReadWriteStream<Buffer> write(Buffer content) {
                buffer.appendBuffer(content);
                return this;
            }

            @Override
            public void end() {
                //Schema factory is not thread safe
                SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                Source xsd = new StreamSource(new StringReader(configuration.getXsdSchema()));
                Source xml = new StreamSource(new StringReader(buffer.toString()));
                try {
                    Schema schema = schemaFactory.newSchema(xsd);
                    Validator validator = schema.newValidator();
                    validator.validate(xml);
                    super.write(buffer);
                    super.end();
                } catch (SAXException | IOException e) {
                    request.metrics().setMessage(e.getMessage());
                    sendErrorResponse(executionContext, policyChain, HttpStatusCode.BAD_REQUEST_400);
                }
            }
        };
    }

    private void sendErrorResponse(ExecutionContext executionContext, PolicyChain policyChain, int httpStatusCode) {
        String errorMessage = null;
        if (configuration.getErrorMessage() != null && !configuration.getErrorMessage().isEmpty()) {
            errorMessage = executionContext.getTemplateEngine().convert(configuration.getErrorMessage());
        } else {
            errorMessage = httpStatusCode == 400 ? BAD_REQUEST : INTERNAL_ERROR;
        }
        policyChain.streamFailWith(PolicyResult.failure(httpStatusCode, errorMessage, MediaType.APPLICATION_XML));
    }
}
