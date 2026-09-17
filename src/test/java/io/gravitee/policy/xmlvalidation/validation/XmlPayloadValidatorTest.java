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
import org.junit.jupiter.api.Test;

class XmlPayloadValidatorTest {

    private static final String XSD =
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
        assertThat(result.summary()).contains("field 'unknown'");
    }
}
