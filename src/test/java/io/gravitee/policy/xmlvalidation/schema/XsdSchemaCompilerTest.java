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
package io.gravitee.policy.xmlvalidation.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.policy.xmlvalidation.validation.XmlPayloadValidator;
import io.gravitee.policy.xmlvalidation.validation.XmlValidationResult;
import io.gravitee.resource.schema_registry.api.ArtifactSchemaBundle;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class XsdSchemaCompilerTest {

    private static final String VALID_XSD =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
          <xs:element name="order">
            <xs:complexType>
              <xs:sequence>
                <xs:element name="customer" type="xs:string"/>
              </xs:sequence>
            </xs:complexType>
          </xs:element>
        </xs:schema>""";

    @Test
    void shouldCompileValidXsd() {
        CompiledXsdSchema compiled = XsdSchemaCompiler.compile(VALID_XSD, SchemaSource.INLINE);
        assertThat(compiled).isNotNull();
        assertThat(compiled.schema()).isNotNull();
    }

    @Test
    void shouldRejectNullContent() {
        assertThatThrownBy(() -> XsdSchemaCompiler.compile(null, SchemaSource.INLINE))
            .isInstanceOf(XsdSchemaResolutionException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void shouldRejectBlankContent() {
        assertThatThrownBy(() -> XsdSchemaCompiler.compile("   ", SchemaSource.INLINE))
            .isInstanceOf(XsdSchemaResolutionException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void shouldRejectNonXsdContent() {
        String notXsd = "<root>not a schema</root>";
        assertThatThrownBy(() -> XsdSchemaCompiler.compile(notXsd, SchemaSource.REGISTRY))
            .isInstanceOf(XsdSchemaResolutionException.class)
            .hasMessageContaining("Unable to compile XSD schema");
    }

    @Test
    void compiledSchemaShouldValidateMatchingXml() {
        CompiledXsdSchema compiled = XsdSchemaCompiler.compile(VALID_XSD, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(compiled, "<order><customer>Alice</customer></order>");
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void compiledSchemaShouldRejectNonMatchingXml() {
        CompiledXsdSchema compiled = XsdSchemaCompiler.compile(VALID_XSD, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(compiled, "<order><unknown>Alice</unknown></order>");
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void shouldRejectOversizedContent() {
        // Create a string that exceeds 10MB
        int sizeOver10MB = 10 * 1024 * 1024 + 1;
        StringBuilder oversizedContent = new StringBuilder(sizeOver10MB);
        oversizedContent.append("<?xml version=\"1.0\"?><xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">");
        while (oversizedContent.length() < sizeOver10MB) {
            oversizedContent.append("<!-- padding to exceed size limit -->");
        }
        oversizedContent.append("</xs:schema>");

        assertThatThrownBy(() -> XsdSchemaCompiler.compile(oversizedContent.toString(), SchemaSource.REGISTRY))
            .isInstanceOf(XsdSchemaResolutionException.class)
            .hasMessageContaining("exceeds maximum allowed size");
    }

    @Test
    void shouldAcceptContentJustUnderSizeLimit() {
        CompiledXsdSchema compiled = XsdSchemaCompiler.compile(VALID_XSD, SchemaSource.INLINE);
        assertThat(compiled).isNotNull();
    }

    // --- Multi-document bundle tests ---

    private static final String ROOT_XSD_WITH_IMPORT =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                   xmlns:addr="urn:address"
                   targetNamespace="urn:order">
          <xs:import namespace="urn:address" schemaLocation="address.xsd"/>
          <xs:element name="order">
            <xs:complexType>
              <xs:sequence>
                <xs:element name="customer" type="xs:string"/>
                <xs:element ref="addr:address"/>
              </xs:sequence>
            </xs:complexType>
          </xs:element>
        </xs:schema>""";

    private static final String ADDRESS_XSD =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                   targetNamespace="urn:address"
                   elementFormDefault="qualified">
          <xs:element name="address">
            <xs:complexType>
              <xs:sequence>
                <xs:element name="city" type="xs:string"/>
                <xs:element name="zip" type="xs:string"/>
              </xs:sequence>
            </xs:complexType>
          </xs:element>
        </xs:schema>""";

    @Test
    void should_compile_multi_document_bundle_with_import() {
        ArtifactSchemaBundle bundle = new ArtifactSchemaBundle(
            ROOT_XSD_WITH_IMPORT.getBytes(StandardCharsets.UTF_8),
            Map.of("address.xsd", ADDRESS_XSD.getBytes(StandardCharsets.UTF_8)),
            "test-digest-multi",
            "test-group",
            "order-schema",
            "1.0"
        );

        CompiledXsdSchema compiled = XsdSchemaCompiler.compile(bundle);
        assertThat(compiled).isNotNull();
        assertThat(compiled.schema()).isNotNull();
    }

    @Test
    void should_validate_xml_against_multi_document_schema() {
        ArtifactSchemaBundle bundle = new ArtifactSchemaBundle(
            ROOT_XSD_WITH_IMPORT.getBytes(StandardCharsets.UTF_8),
            Map.of("address.xsd", ADDRESS_XSD.getBytes(StandardCharsets.UTF_8)),
            "test-digest-validate",
            "test-group",
            "order-schema",
            "1.0"
        );

        CompiledXsdSchema compiled = XsdSchemaCompiler.compile(bundle);
        String validXml =
            """
            <ord:order xmlns:ord="urn:order" xmlns:addr="urn:address">
              <customer>Alice</customer>
              <addr:address>
                <addr:city>Paris</addr:city>
                <addr:zip>75001</addr:zip>
              </addr:address>
            </ord:order>""";
        XmlValidationResult result = XmlPayloadValidator.validate(compiled, validXml);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void should_reject_xml_against_multi_document_schema_with_missing_field() {
        ArtifactSchemaBundle bundle = new ArtifactSchemaBundle(
            ROOT_XSD_WITH_IMPORT.getBytes(StandardCharsets.UTF_8),
            Map.of("address.xsd", ADDRESS_XSD.getBytes(StandardCharsets.UTF_8)),
            "test-digest-reject",
            "test-group",
            "order-schema",
            "1.0"
        );

        CompiledXsdSchema compiled = XsdSchemaCompiler.compile(bundle);
        String invalidXml =
            """
            <ord:order xmlns:ord="urn:order">
              <customer>Alice</customer>
            </ord:order>""";
        XmlValidationResult result = XmlPayloadValidator.validate(compiled, invalidXml);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void should_fail_compile_when_imported_document_is_missing_from_bundle() {
        ArtifactSchemaBundle bundle = new ArtifactSchemaBundle(
            ROOT_XSD_WITH_IMPORT.getBytes(StandardCharsets.UTF_8),
            Map.of(),
            "test-digest-missing-import",
            "test-group",
            "order-schema",
            "1.0"
        );

        assertThatThrownBy(() -> XsdSchemaCompiler.compile(bundle))
            .isInstanceOf(XsdSchemaResolutionException.class)
            .hasMessageContaining("Unable to compile XSD schema");
    }

    @Test
    void should_block_external_http_schema_location_in_bundle() {
        String rootWithExternalImport =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:import namespace="urn:evil" schemaLocation="https://evil.example.com/evil.xsd"/>
              <xs:element name="root" type="xs:string"/>
            </xs:schema>""";

        ArtifactSchemaBundle bundle = new ArtifactSchemaBundle(
            rootWithExternalImport.getBytes(StandardCharsets.UTF_8),
            Map.of(),
            "test-digest-external-block",
            "test-group",
            "evil-schema",
            "1.0"
        );

        assertThatThrownBy(() -> XsdSchemaCompiler.compile(bundle)).isInstanceOf(XsdSchemaResolutionException.class);
    }

    @Test
    void should_reject_null_bundle() {
        assertThatThrownBy(() -> XsdSchemaCompiler.compile((ArtifactSchemaBundle) null))
            .isInstanceOf(XsdSchemaResolutionException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void should_reject_empty_root_content_in_bundle() {
        ArtifactSchemaBundle bundle = new ArtifactSchemaBundle(
            new byte[0],
            Map.of(),
            "test-digest-empty",
            "test-group",
            "empty-schema",
            "1.0"
        );

        assertThatThrownBy(() -> XsdSchemaCompiler.compile(bundle))
            .isInstanceOf(XsdSchemaResolutionException.class)
            .hasMessageContaining("empty");
    }
}
