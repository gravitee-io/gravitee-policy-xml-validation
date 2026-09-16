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
import static org.assertj.core.api.Assertions.assertThatNoException;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

class SecureXmlTest {

    private static final String XSD =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
          <xs:element name="root" type="xs:string"/>
        </xs:schema>""";

    @Test
    void schemaFactoryShouldBeCreatedWithoutExceptions() {
        assertThatNoException().isThrownBy(SecureXml::newSchemaFactory);
    }

    @Test
    void schemaFactoryShouldEnableSecureProcessing() throws Exception {
        var schemaFactory = SecureXml.newSchemaFactory();
        assertThat(schemaFactory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING)).isTrue();
    }

    @Test
    void saxParserFactoryShouldBeCreatedWithoutExceptions() {
        assertThatNoException().isThrownBy(SecureXml::newSaxParserFactory);
    }

    @Test
    void saxParserShouldDisallowDoctypeDecl() throws Exception {
        SAXParserFactory factory = SecureXml.newSaxParserFactory();
        assertThat(factory.getFeature("http://apache.org/xml/features/disallow-doctype-decl")).isTrue();
    }

    @Test
    void saxParserShouldDisableExternalGeneralEntities() throws Exception {
        SAXParserFactory factory = SecureXml.newSaxParserFactory();
        assertThat(factory.getFeature("http://xml.org/sax/features/external-general-entities")).isFalse();
    }

    @Test
    void saxParserShouldDisableExternalParameterEntities() throws Exception {
        SAXParserFactory factory = SecureXml.newSaxParserFactory();
        assertThat(factory.getFeature("http://xml.org/sax/features/external-parameter-entities")).isFalse();
    }

    @Test
    void saxParserShouldEnableSecureProcessing() throws Exception {
        SAXParserFactory factory = SecureXml.newSaxParserFactory();
        assertThat(factory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING)).isTrue();
    }

    @Test
    void validatorShouldRejectDoctypePayload() throws Exception {
        String xmlWithDoctype =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE foo [
              <!ELEMENT root (#PCDATA)>
            ]>
            <root>content</root>""";

        Schema schema = SecureXml.newSchemaFactory().newSchema(new javax.xml.transform.stream.StreamSource(new java.io.StringReader(XSD)));
        Validator validator = schema.newValidator();
        SAXParser saxParser = SecureXml.newSaxParserFactory().newSAXParser();

        boolean caught = false;
        try {
            validator.validate(
                new javax.xml.transform.sax.SAXSource(
                    saxParser.getXMLReader(),
                    new org.xml.sax.InputSource(new java.io.StringReader(xmlWithDoctype))
                )
            );
        } catch (SAXParseException e) {
            caught = true;
            assertThat(e.getMessage()).contains("DOCTYPE");
        }
        assertThat(caught).as("XXE doctype payload must be rejected").isTrue();
    }
}
