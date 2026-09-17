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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.MediaType;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.policy.v3.xmlvalidation.XmlValidationPolicyV3;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.schema.CompiledXsdSchema;
import io.gravitee.policy.xmlvalidation.schema.XsdSchemaResolutionException;
import io.gravitee.policy.xmlvalidation.validation.XmlPayloadValidator;
import io.gravitee.policy.xmlvalidation.validation.XmlValidationViolation;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class XmlValidationPolicy extends XmlValidationPolicyV3 implements HttpPolicy {

    public static final String XML_VALIDATION_FAILED = "XML_VALIDATION_FAILED";
    public static final String XML_VALIDATION_SCHEMA_REGISTRY_UNREACHABLE = "XML_VALIDATION_SCHEMA_REGISTRY_UNREACHABLE";
    public static final String XML_VALIDATION_SCHEMA_ARTIFACT_NOT_FOUND = "XML_VALIDATION_SCHEMA_ARTIFACT_NOT_FOUND";
    public static final String XML_VALIDATION_SCHEMA_REGISTRY_NOT_READY = "XML_VALIDATION_SCHEMA_REGISTRY_NOT_READY";
    public static final String XML_VALIDATION_SCHEMA_COMPILE_FAILED = "XML_VALIDATION_SCHEMA_COMPILE_FAILED";
    public static final String XML_VALIDATION_SCHEMA_CLOSURE_LIMIT_EXCEEDED = "XML_VALIDATION_SCHEMA_CLOSURE_LIMIT_EXCEEDED";
    public static final String XML_VALIDATION_SCHEMA_CONFIGURATION_INVALID = "XML_VALIDATION_SCHEMA_CONFIGURATION_INVALID";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public XmlValidationPolicy(XmlValidationPolicyConfiguration configuration) {
        super(configuration, false);
    }

    @Override
    public String id() {
        return "xml-validation";
    }

    @Override
    public Completable onRequest(HttpPlainExecutionContext ctx) {
        return schemaHolder
            .ensureReady(ctx)
            .andThen(ctx.request().bodyOrEmpty().flatMapCompletable(buffer -> validate(ctx, buffer)))
            .onErrorResumeNext(error -> {
                if (error instanceof XsdSchemaResolutionException resolutionException) {
                    return handleResolutionFailure(ctx, resolutionException);
                }
                if (error instanceof IllegalStateException) {
                    return handleResolutionFailure(
                        ctx,
                        new XsdSchemaResolutionException(error.getMessage(), configuration.getSchemaSource(), error)
                    );
                }
                return Completable.error(error);
            });
    }

    private Completable validate(HttpPlainExecutionContext ctx, Buffer buffer) {
        CompiledXsdSchema compiledXsdSchema = schemaHolder.compiledSchema();
        String xml = buffer.toString(StandardCharsets.UTF_8);

        return Maybe
            .fromCallable(() -> XmlPayloadValidator.validate(compiledXsdSchema, xml))
            .subscribeOn(Schedulers.computation())
            .flatMapCompletable(result -> {
                if (result.isValid()) {
                    return Completable.complete();
                }

                log.debug("Invalid XML payload: {}", result.summary());
                ctx.metrics().setErrorMessage(result.summary());

                List<Map<String, Object>> violations = result.violations().stream().map(this::toViolationMap).collect(Collectors.toList());

                Map<String, Object> parameters = new HashMap<>();
                parameters.put("violations", violations);

                return resolveErrorMessage(ctx)
                    .flatMapCompletable(errorMsg ->
                        interrupt(
                            ctx,
                            new ExecutionFailure(400)
                                .contentType(MediaType.APPLICATION_JSON)
                                .key(XML_VALIDATION_FAILED)
                                .message(buildResponseBody(errorMsg, violations))
                                .parameters(parameters)
                        )
                    );
            });
    }

    private Map<String, Object> toViolationMap(XmlValidationViolation violation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("line", violation.line());
        map.put("column", violation.column());
        map.put("path", violation.element());
        map.put("message", violation.message());
        if (violation.code() != null) {
            map.put("code", violation.code());
        }
        return map;
    }

    private Completable handleResolutionFailure(HttpPlainExecutionContext ctx, XsdSchemaResolutionException error) {
        ctx.metrics().setErrorMessage(error.getMessage());
        String key =
            switch (error.getFailureKind()) {
                case NOT_FOUND -> XML_VALIDATION_SCHEMA_ARTIFACT_NOT_FOUND;
                case NOT_READY -> XML_VALIDATION_SCHEMA_REGISTRY_NOT_READY;
                case UNREACHABLE -> XML_VALIDATION_SCHEMA_REGISTRY_UNREACHABLE;
                case COMPILE -> XML_VALIDATION_SCHEMA_COMPILE_FAILED;
                case CLOSURE_LIMIT -> XML_VALIDATION_SCHEMA_CLOSURE_LIMIT_EXCEEDED;
                case CONFIGURATION, OTHER -> XML_VALIDATION_SCHEMA_CONFIGURATION_INVALID;
            };
        int httpStatus =
            switch (error.getFailureKind()) {
                case UNREACHABLE, NOT_READY -> 503;
                case COMPILE -> 500;
                case NOT_FOUND -> 404;
                case CLOSURE_LIMIT, CONFIGURATION, OTHER -> 400;
            };
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("registryResource", error.getRegistryResource());
        parameters.put("groupId", error.getGroupId());
        parameters.put("artifactId", error.getArtifactId());
        parameters.put("version", error.getVersion());
        return interrupt(
            ctx,
            new ExecutionFailure(httpStatus)
                .contentType(MediaType.APPLICATION_JSON)
                .key(key)
                .message(buildErrorResponseBody(error.getMessage()))
                .parameters(parameters)
                .cause(error)
        );
    }

    private String buildErrorResponseBody(String errorMessage) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", errorMessage);
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize error body: {}", e.getMessage());
            return errorMessage;
        }
    }

    private Maybe<String> resolveErrorMessage(HttpPlainExecutionContext ctx) {
        String configuredMessage = configuration.getErrorMessage();
        if (configuredMessage != null && !configuredMessage.isEmpty()) {
            return ctx
                .getTemplateEngine()
                .eval(configuredMessage, String.class)
                .switchIfEmpty(Maybe.just("XML validation failed"))
                .onErrorResumeNext(e -> {
                    log.warn("Failed to evaluate error message expression: {}", e.getMessage());
                    return Maybe.just("XML validation failed");
                });
        }
        return Maybe.just("XML validation failed");
    }

    private String buildResponseBody(String errorMessage, List<Map<String, Object>> violations) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", errorMessage);
            body.put("violations", violations);
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize error body: {}", e.getMessage());
            return errorMessage;
        }
    }

    private static Completable interrupt(HttpPlainExecutionContext ctx, ExecutionFailure executionFailure) {
        return ctx.interruptWith(executionFailure);
    }
}
