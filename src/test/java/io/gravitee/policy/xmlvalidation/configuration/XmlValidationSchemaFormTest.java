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
package io.gravitee.policy.xmlvalidation.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Draft7;
import com.networknt.schema.keyword.AnnotationKeyword;
import io.gravitee.json.validation.InvalidJsonException;
import io.gravitee.json.validation.JsonSchemaValidatorImpl;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class XmlValidationSchemaFormTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Same Draft-7 + legacy `id` annotation as JsonSchemaValidatorImpl, but without fill-default/re-validate.
    private static final SchemaRegistry STRICT_SCHEMA_REGISTRY = SchemaRegistry.withDialect(
        Dialect.builder(Draft7.getInstance()).keyword(new AnnotationKeyword("id")).build()
    );

    private String schema;
    private JsonSchemaValidatorImpl validator;

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/schemas/schema-form.json")) {
            assertThat(in).isNotNull();
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        validator = new JsonSchemaValidatorImpl();
    }

    @Test
    void should_accept_legacy_inline_config_without_schemaSource() {
        String configuration = """
            {"xsdSchema":"<xs:schema xmlns:xs=\\"http://www.w3.org/2001/XMLSchema\\"/>","errorMessage":"<error>bad</error>"}
            """;

        assertThatCode(() -> validator.validate(schema, configuration)).doesNotThrowAnyException();
    }

    @Test
    void should_accept_explicit_inline_config() {
        String configuration = """
            {"schemaSource":"inline","xsdSchema":"<xs:schema xmlns:xs=\\"http://www.w3.org/2001/XMLSchema\\"/>"}
            """;

        assertThatCode(() -> validator.validate(schema, configuration)).doesNotThrowAnyException();
    }

    @Test
    void should_require_registry_fields_only_when_schemaSource_is_registry() {
        String configuration = """
            {"schemaSource":"registry"}
            """;

        assertThatThrownBy(() -> validator.validate(schema, configuration))
            .isInstanceOf(InvalidJsonException.class)
            .hasMessageContaining("registryResource")
            .hasMessageContaining("groupId")
            .hasMessageContaining("artifactId")
            .hasMessageContaining("version");
    }

    @Test
    void should_require_xsdSchema_when_not_registry() {
        assertThatThrownBy(() -> validator.validate(schema, "{}"))
            .isInstanceOf(InvalidJsonException.class)
            .hasMessageContaining("xsdSchema");

        assertThatThrownBy(() ->
            validator.validate(
                schema,
                """
                {"schemaSource":"inline"}
                """
            )
        )
            .isInstanceOf(InvalidJsonException.class)
            .hasMessageContaining("xsdSchema");
    }

    @Test
    void should_accept_legacy_inline_fixture_through_schema_validator() throws IOException {
        String configuration = readResource("/io/gravitee/policy/xmlvalidation/configuration/configuration-legacy-inline.json");

        assertThatCode(() -> validator.validate(schema, configuration)).doesNotThrowAnyException();
    }

    @Test
    void should_accept_legacy_inline_config_without_filling_defaults() throws Exception {
        String configuration = """
            {"xsdSchema":"<xs:schema xmlns:xs=\\"http://www.w3.org/2001/XMLSchema\\"/>","errorMessage":"<error>bad</error>"}
            """;

        List<Error> errors = STRICT_SCHEMA_REGISTRY.getSchema(schema).validate(MAPPER.readTree(configuration));

        assertThat(errors).isEmpty();
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = XmlValidationSchemaFormTest.class.getResourceAsStream(path)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
