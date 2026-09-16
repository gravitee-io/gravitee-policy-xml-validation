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
package io.gravitee.policy.v3.xmlvalidation;

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
import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.policy.xmlvalidation.schema.CompiledXsdSchema;
import io.gravitee.policy.xmlvalidation.schema.CompiledXsdSchemaHolder;
import io.gravitee.policy.xmlvalidation.schema.XsdSchemaResolutionException;
import io.gravitee.policy.xmlvalidation.validation.XmlPayloadValidator;
import io.gravitee.policy.xmlvalidation.validation.XmlValidationResult;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public class XmlValidationPolicyV3 {

    protected static final String XML_INVALID_PAYLOAD_KEY = "XML_INVALID_PAYLOAD";
    protected static final String XML_INVALID_FORMAT_KEY = "XML_INVALID_FORMAT";
    protected static final String BAD_REQUEST = "Bad Request";

    private static final Logger LOGGER = LoggerFactory.getLogger(XmlValidationPolicyV3.class);

    protected final XmlValidationPolicyConfiguration configuration;
    protected final CompiledXsdSchemaHolder schemaHolder;

    public XmlValidationPolicyV3(XmlValidationPolicyConfiguration configuration) {
        this(configuration, true);
    }

    /**
     * V3 policies run on the legacy stream engine, which cannot resolve schema-registry resources.
     * When constructed for V3, a registry-sourced schema is rejected at deployment time so the API
     * fails fast instead of failing every request at runtime. V4 policies pass {@code false} and
     * resolve registry schemas reactively at first request.
     */
    protected XmlValidationPolicyV3(XmlValidationPolicyConfiguration configuration, boolean rejectRegistrySource) {
        this.configuration = configuration;
        if (rejectRegistrySource && configuration.getSchemaSource() == SchemaSource.REGISTRY) {
            throw new IllegalArgumentException("Schema registry sources are not supported by the V3 execution engine; use a V4 API");
        }
        this.schemaHolder = CompiledXsdSchemaHolder.forConfiguration(configuration);
    }

    @OnRequestContent
    public ReadWriteStream<Buffer> onRequestContent(
        Request request,
        Response response,
        ExecutionContext executionContext,
        PolicyChain policyChain
    ) {
        return new BufferedReadWriteStream() {
            private final Buffer buffer = Buffer.buffer();

            @Override
            public SimpleReadWriteStream<Buffer> write(Buffer content) {
                buffer.appendBuffer(content);
                return this;
            }

            @Override
            public void end() {
                try {
                    CompiledXsdSchema compiledXsdSchema = schemaHolder.compiledSchema();
                    XmlValidationResult result = XmlPayloadValidator.validate(compiledXsdSchema, buffer.toString(StandardCharsets.UTF_8));
                    if (result.isValid()) {
                        super.write(buffer);
                        super.end();
                        return;
                    }

                    String validationMessage = result.summary();
                    request.metrics().setMessage(validationMessage);
                    String responseMessage = resolveResponseMessage(executionContext, validationMessage, HttpStatusCode.BAD_REQUEST_400);
                    policyChain.streamFailWith(
                        PolicyResult.failure(XML_INVALID_PAYLOAD_KEY, HttpStatusCode.BAD_REQUEST_400, responseMessage, MediaType.TEXT_PLAIN)
                    );
                } catch (XsdSchemaResolutionException | IllegalStateException ex) {
                    request.metrics().setMessage(ex.getMessage());
                    sendErrorResponse(
                        executionContext,
                        policyChain,
                        HttpStatusCode.BAD_REQUEST_400,
                        XML_INVALID_FORMAT_KEY,
                        ex.getMessage()
                    );
                }
            }
        };
    }

    protected String resolveResponseMessage(ExecutionContext executionContext, String validationMessage, int httpStatusCode) {
        return validationMessage;
    }

    protected void sendErrorResponse(
        ExecutionContext executionContext,
        PolicyChain policyChain,
        int httpStatusCode,
        String key,
        String fallbackMessage
    ) {
        String configuredMessage = configuration.getErrorMessage();
        String message;
        if (configuredMessage != null && !configuredMessage.isEmpty()) {
            message = executionContext.getTemplateEngine().convert(configuredMessage);
        } else {
            message = fallbackMessage != null ? fallbackMessage : BAD_REQUEST;
        }
        policyChain.streamFailWith(PolicyResult.failure(key, httpStatusCode, message, MediaType.TEXT_PLAIN));
    }
}
