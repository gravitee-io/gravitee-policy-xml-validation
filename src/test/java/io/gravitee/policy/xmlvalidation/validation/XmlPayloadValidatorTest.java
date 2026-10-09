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
package io.gravitee.policy.xmlvalidation.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.policy.xmlvalidation.schema.CompiledXsdSchema;
import io.gravitee.policy.xmlvalidation.schema.XsdSchemaCompiler;
import java.util.List;
import org.junit.jupiter.api.Test;

class XmlPayloadValidatorTest {

    private static final String XSD = """
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
    void shouldValidateMatchingPayload() {
        CompiledXsdSchema schema = XsdSchemaCompiler.compile(XSD, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(schema, "<order><customer>Alice</customer></order>");
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void shouldRejectEmptyPayload() {
        CompiledXsdSchema schema = XsdSchemaCompiler.compile(XSD, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(schema, "");
        assertThat(result.isValid()).isFalse();
        assertThat(result.summary()).contains("XML payload is empty");
    }

    @Test
    void shouldReportInvalidElement() {
        CompiledXsdSchema schema = XsdSchemaCompiler.compile(XSD, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(schema, "<order><unknown>Alice</unknown></order>");
        assertThat(result.isValid()).isFalse();
        assertThat(result.summary()).contains("unknown");
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst().path()).isEqualTo("/order/unknown");
    }

    @Test
    void shouldNotDuplicateFatalParseErrors() {
        CompiledXsdSchema schema = XsdSchemaCompiler.compile(XSD, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(schema, "<order><customer>Alice</customer>");
        assertThat(result.isValid()).isFalse();
        assertThat(result.violations()).hasSize(1);
    }

    @Test
    void shouldDedupeGenericTypeAndFacetCodesAtSameLocation() {
        String xsd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:test:order"
                       xmlns="urn:test:order" elementFormDefault="qualified">
              <xs:element name="order">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="sku">
                      <xs:simpleType>
                        <xs:restriction base="xs:string">
                          <xs:pattern value="[A-Z]{3}-[0-9]{4}"/>
                        </xs:restriction>
                      </xs:simpleType>
                    </xs:element>
                    <xs:element name="quantity">
                      <xs:simpleType>
                        <xs:restriction base="xs:positiveInteger">
                          <xs:minInclusive value="1"/>
                        </xs:restriction>
                      </xs:simpleType>
                    </xs:element>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:schema>""";
        CompiledXsdSchema schema = XsdSchemaCompiler.compile(xsd, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(
            schema,
            """
            <order xmlns="urn:test:order">
              <sku>abc</sku>
              <quantity>0</quantity>
            </order>"""
        );
        assertThat(result.isValid()).isFalse();
        assertThat(result.violations()).hasSize(2);
        assertThat(result.violations())
            .extracting(XmlValidationViolation::code)
            .noneMatch(code -> code != null && code.startsWith("cvc-type."));
        assertThat(result.violations()).allSatisfy(v -> {
            assertThat(v.element()).isNotBlank();
            assertThat(v.path()).isNotBlank();
        });
    }

    @Test
    void shouldKeepDistinctConstraintCodesAtTheSameLocation() {
        XmlValidationViolation generic = new XmlValidationViolation(2, 10, "sku", "type", "cvc-type.3.1.3", "/order/sku");
        XmlValidationViolation pattern = new XmlValidationViolation(2, 10, "sku", "pattern", "cvc-pattern-valid", "/order/sku");
        XmlValidationViolation length = new XmlValidationViolation(2, 10, "sku", "length", "cvc-length-valid", "/order/sku");
        XmlValidationViolation duplicate = new XmlValidationViolation(2, 10, null, "pattern again", "cvc-pattern-valid", null);

        assertThat(XmlPayloadValidator.dedupe(List.of(generic, pattern, length, duplicate)))
            .extracting(XmlValidationViolation::message)
            .containsExactly("pattern", "length", "pattern again");
    }

    @Test
    void shouldReportEveryMissingRequiredAttribute() {
        CompiledXsdSchema schema = XsdSchemaCompiler.compile(ATTRIBUTE_XSD, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(schema, "<order><item/></order>");
        assertThat(result.isValid()).isFalse();
        assertThat(result.violations())
            .extracting(XmlValidationViolation::message)
            .anyMatch(message -> message.contains("'a'"));
        assertThat(result.violations())
            .extracting(XmlValidationViolation::message)
            .anyMatch(message -> message.contains("'b'"));
    }

    @Test
    void shouldReportPatternErrorsForEachAttribute() {
        CompiledXsdSchema schema = XsdSchemaCompiler.compile(ATTRIBUTE_XSD, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(schema, "<order><item a=\"x\" b=\"y\"/></order>");
        assertThat(result.isValid()).isFalse();
        assertThat(result.violations())
            .extracting(XmlValidationViolation::message)
            .anyMatch(message -> message.contains("'a'"));
        assertThat(result.violations())
            .extracting(XmlValidationViolation::message)
            .anyMatch(message -> message.contains("'b'"));
    }

    @Test
    void shouldIndexRepeatedSiblingElements() {
        CompiledXsdSchema schema = XsdSchemaCompiler.compile(ATTRIBUTE_XSD, SchemaSource.INLINE);
        XmlValidationResult result = XmlPayloadValidator.validate(
            schema,
            "<order><item a=\"x\" b=\"ABC\"/><item a=\"x\" b=\"ABC\"/></order>"
        );
        assertThat(result.violations()).extracting(XmlValidationViolation::path).contains("/order/item", "/order/item[2]");
    }

    private static final String ATTRIBUTE_XSD = """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
          <xs:element name="order">
            <xs:complexType>
              <xs:sequence>
                <xs:element name="item" maxOccurs="unbounded">
                  <xs:complexType>
                    <xs:attribute name="a" use="required">
                      <xs:simpleType>
                        <xs:restriction base="xs:string"><xs:pattern value="[A-Z]{3}"/></xs:restriction>
                      </xs:simpleType>
                    </xs:attribute>
                    <xs:attribute name="b" use="required">
                      <xs:simpleType>
                        <xs:restriction base="xs:string"><xs:pattern value="[A-Z]{3}"/></xs:restriction>
                      </xs:simpleType>
                    </xs:attribute>
                  </xs:complexType>
                </xs:element>
              </xs:sequence>
            </xs:complexType>
          </xs:element>
        </xs:schema>""";
}
