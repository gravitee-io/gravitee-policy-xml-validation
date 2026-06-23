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

import static io.gravitee.policy.xmlvalidation.xsd.XsdResolverFactory.create;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.stream.BufferedReadWriteStream;
import io.gravitee.gateway.api.stream.ReadWriteStream;
import io.gravitee.gateway.api.stream.SimpleReadWriteStream;
import io.gravitee.policy.XmlValidationException;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequestContent;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.xsd.ValidatableXsdResolver;
import io.gravitee.policy.xmlvalidation.xsd.XsdCompiler;
import io.gravitee.policy.xmlvalidation.xsd.XsdResolver;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.validation.Schema;
import org.xml.sax.SAXException;

public class XmlValidationPolicy {

    private static final Logger log = Logger.getLogger(XmlValidationPolicy.class.getName());

    // Error keys — used as machine-readable codes in the response body and in PolicyResult
    static final String ERROR_KEY_SCHEMA_REGISTRY = "XML_SCHEMA_REGISTRY_ERROR";
    static final String ERROR_KEY_SCHEMA_COMPILE = "XML_SCHEMA_COMPILE_ERROR";
    static final String ERROR_KEY_VALIDATION = "XML_VALIDATION_ERROR";

    private final XmlValidationPolicyConfiguration configuration;
    private final XsdResolver xsdResolver;

    public XmlValidationPolicy(XmlValidationPolicyConfiguration configuration) throws SAXException, IOException {
        this.configuration = configuration;
        this.xsdResolver = create(configuration);
        validateXsdResolver();
    }

    @OnRequestContent
    public ReadWriteStream onRequestContent(
        Request request,
        Response response,
        ExecutionContext executionContext,
        PolicyChain policyChain
    ) {
        log.fine(() -> "Execute XML validation policy on request " + request.id());
        return new BufferedReadWriteStream() {
            Buffer buffer = Buffer.buffer();

            @Override
            public SimpleReadWriteStream<Buffer> write(Buffer content) {
                buffer.appendBuffer(content);
                return this;
            }

            @Override
            public void end() {
                // resolveXsd() returns a cold Single — no blocking, no Vert.x scheduler interaction.
                // Each resolver manages its own threading (static = immediate, resource = reactive
                // chain, confluent = dedicated non-Vert.x thread pool).
                xsdResolver.resolveXsd(executionContext)
                    .subscribe(
                        xsd -> {
                            // Block 2: compile the XSD — malformed schema = operator error → 500
                            final Schema schema;
                            try {
                                schema = XsdCompiler.compile(xsd);
                            } catch (SAXException | IOException e) {
                                log.log(Level.SEVERE, "XSD schema compilation failed during XML validation", e);
                                request.metrics().setMessage(e.getMessage());
                                sendErrorResponse(
                                    executionContext,
                                    policyChain,
                                    HttpStatusCode.INTERNAL_SERVER_ERROR_500,
                                    ERROR_KEY_SCHEMA_COMPILE,
                                    e.getMessage()
                                );
                                return;
                            }

                            // Block 3: validate the request XML — bad client data → 400
                            try {
                                XsdCompiler.validate(schema, buffer.toString());
                                super.write(buffer);
                                super.end();
                            } catch (SAXException | IOException e) {
                                log.fine(() -> "XML payload failed schema validation: " + e.getMessage());
                                request.metrics().setMessage(e.getMessage());
                                sendErrorResponse(
                                    executionContext,
                                    policyChain,
                                    HttpStatusCode.BAD_REQUEST_400,
                                    ERROR_KEY_VALIDATION,
                                    e.getMessage()
                                );
                            }
                        },
                        error -> {
                            // Block 1 error: registry/resolution failure → 500
                            log.log(Level.SEVERE, "Schema registry error during XML validation", error);
                            request.metrics().setMessage(error.getMessage());
                            sendErrorResponse(
                                executionContext,
                                policyChain,
                                HttpStatusCode.INTERNAL_SERVER_ERROR_500,
                                ERROR_KEY_SCHEMA_REGISTRY,
                                error.getMessage()
                            );
                        }
                    );
            }
        };
    }

    private void validateXsdResolver() throws SAXException, IOException {
        if (xsdResolver instanceof ValidatableXsdResolver validatableXsdResolver) {
            validatableXsdResolver.validate();
        }
    }

    /**
     * Sends a structured XML error response. When the operator has configured a custom errorMessage
     * template it is used as-is (evaluated via SpEL). Otherwise a structured XML body is built with
     * the error code, HTTP status, and the specific detail message so callers know exactly what failed.
     */
    private void sendErrorResponse(
        ExecutionContext executionContext,
        PolicyChain policyChain,
        int httpStatusCode,
        String errorKey,
        String detail
    ) {
        final String errorMessage;
        if (configuration.getErrorMessage() != null && !configuration.getErrorMessage().isEmpty()) {
            errorMessage = executionContext.getTemplateEngine().convert(configuration.getErrorMessage());
        } else {
            errorMessage = buildXmlErrorBody(httpStatusCode, errorKey, detail);
        }
        policyChain.streamFailWith(PolicyResult.failure(errorKey, httpStatusCode, errorMessage, MediaType.APPLICATION_XML));
    }

    /**
     * Builds a structured XML error body, e.g.:
     * <pre>
     * &lt;?xml version="1.0" encoding="UTF-8"?&gt;
     * &lt;error&gt;
     *   &lt;code&gt;XML_VALIDATION_ERROR&lt;/code&gt;
     *   &lt;status&gt;400&lt;/status&gt;
     *   &lt;message&gt;XML validation failed: cvc-complex-type.2.4.a: ...&lt;/message&gt;
     * &lt;/error&gt;
     * </pre>
     */
    private static String buildXmlErrorBody(int status, String code, String detail) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<error>\n  <code>" +
            escapeXml(code) +
            "</code>\n  <status>" +
            status +
            "</status>\n  <message>" +
            escapeXml(detail) +
            "</message>\n</error>";
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
