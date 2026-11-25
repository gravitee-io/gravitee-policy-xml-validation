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
import io.gravitee.resource.schema_registry.api.SchemaClosureLimitExceededException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public class XmlValidationPolicyV3 {

    protected static final String XML_INVALID_PAYLOAD_KEY = "XML_INVALID_PAYLOAD";
    protected static final String XML_INVALID_FORMAT_KEY = "XML_INVALID_FORMAT";
    protected static final String XML_VALIDATION_SCHEMA_COMPILE_FAILED = "XML_VALIDATION_SCHEMA_COMPILE_FAILED";
    protected static final String BAD_REQUEST = "Bad Request";
    protected static final String CLIENT_MESSAGE_NOT_FOUND = "Referenced schema artifact was not found";
    protected static final String CLIENT_MESSAGE_NOT_READY = "Schema registry is not ready";
    protected static final String CLIENT_MESSAGE_UNREACHABLE = "Schema registry is temporarily unavailable";
    protected static final String CLIENT_MESSAGE_COMPILE = "Unable to compile the XSD schema";
    protected static final String CLIENT_MESSAGE_CLOSURE_LIMIT_ARTIFACTS = "Schema closure exceeds the maximum artifacts limit";
    protected static final String CLIENT_MESSAGE_CLOSURE_LIMIT_BYTES = "Schema closure exceeds the maximum bytes limit";
    protected static final String CLIENT_MESSAGE_CLOSURE_LIMIT_DEPTH = "Schema closure exceeds the maximum import depth limit";
    protected static final String CLIENT_MESSAGE_CLOSURE_LIMIT_GENERIC = "Schema closure exceeds configured limits";
    protected static final String CLIENT_MESSAGE_CONFIGURATION = "XML validation schema configuration is invalid";
    protected static final String CLIENT_MESSAGE_OTHER = "Unable to resolve the XSD schema";

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
                    CompiledXsdSchema compiledXsdSchema = compiledSchema();
                    XmlValidationResult result = XmlPayloadValidator.validate(compiledXsdSchema, buffer.toString(StandardCharsets.UTF_8));
                    if (result.isValid()) {
                        super.write(buffer);
                        super.end();
                        return;
                    }

                    String validationMessage = result.summary();
                    request.metrics().setMessage(validationMessage);
                    String responseMessage = resolveResponseMessage(executionContext, validationMessage);
                    policyChain.streamFailWith(
                        PolicyResult.failure(
                            XML_INVALID_PAYLOAD_KEY,
                            HttpStatusCode.BAD_REQUEST_400,
                            responseMessage,
                            MediaType.APPLICATION_XML
                        )
                    );
                } catch (XsdSchemaResolutionException ex) {
                    request.metrics().setMessage(ex.getMessage());
                    if (ex.getFailureKind() == XsdSchemaResolutionException.FailureKind.COMPILE) {
                        policyChain.streamFailWith(
                            PolicyResult.failure(
                                XML_VALIDATION_SCHEMA_COMPILE_FAILED,
                                HttpStatusCode.INTERNAL_SERVER_ERROR_500,
                                CLIENT_MESSAGE_COMPILE,
                                MediaType.TEXT_PLAIN
                            )
                        );
                    } else {
                        // Keep the detail in the metrics only: it may hold internal registry text.
                        sendErrorResponse(
                            executionContext,
                            policyChain,
                            HttpStatusCode.BAD_REQUEST_400,
                            XML_INVALID_FORMAT_KEY,
                            clientMessage(ex)
                        );
                    }
                } catch (IllegalStateException ex) {
                    request.metrics().setMessage(ex.getMessage());
                    sendErrorResponse(
                        executionContext,
                        policyChain,
                        HttpStatusCode.BAD_REQUEST_400,
                        XML_INVALID_FORMAT_KEY,
                        CLIENT_MESSAGE_OTHER
                    );
                }
            }
        };
    }

    /**
     * The compiled schema the payload is validated against.
     */
    protected CompiledXsdSchema compiledSchema() {
        return schemaHolder.compiledSchema();
    }

    /**
     * The message sent to the caller when the schema cannot be resolved. It never holds the text of the failure,
     * which may come from the schema registry or an identity provider: that text is kept in the metrics and logs.
     */
    protected static String clientMessage(XsdSchemaResolutionException error) {
        return switch (error.getFailureKind()) {
            case NOT_FOUND -> CLIENT_MESSAGE_NOT_FOUND;
            case NOT_READY -> CLIENT_MESSAGE_NOT_READY;
            case UNREACHABLE -> CLIENT_MESSAGE_UNREACHABLE;
            case COMPILE -> CLIENT_MESSAGE_COMPILE;
            case CLOSURE_LIMIT -> closureLimitClientMessage(error.getClosureLimitKind());
            case CONFIGURATION -> CLIENT_MESSAGE_CONFIGURATION;
            case OTHER -> CLIENT_MESSAGE_OTHER;
        };
    }

    private static String closureLimitClientMessage(SchemaClosureLimitExceededException.ClosureLimitKind limitKind) {
        if (limitKind == null) {
            return CLIENT_MESSAGE_CLOSURE_LIMIT_GENERIC;
        }
        return switch (limitKind) {
            case ARTIFACTS -> CLIENT_MESSAGE_CLOSURE_LIMIT_ARTIFACTS;
            case BYTES -> CLIENT_MESSAGE_CLOSURE_LIMIT_BYTES;
            case IMPORT_DEPTH -> CLIENT_MESSAGE_CLOSURE_LIMIT_DEPTH;
            case UNKNOWN -> CLIENT_MESSAGE_CLOSURE_LIMIT_GENERIC;
        };
    }

    /**
     * Prefer the configured {@code errorMessage} (EL-evaluated) when set, matching legacy V3 inline
     * behaviour. Metrics still keep the XSD violation summary from the caller.
     */
    protected String resolveResponseMessage(ExecutionContext executionContext, String validationMessage) {
        String configuredMessage = configuration.getErrorMessage();
        if (configuredMessage != null && !configuredMessage.isEmpty()) {
            return executionContext.getTemplateEngine().convert(configuredMessage);
        }
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
