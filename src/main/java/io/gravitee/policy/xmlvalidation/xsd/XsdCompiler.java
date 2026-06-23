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
package io.gravitee.policy.xmlvalidation.xsd;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.SAXException;

public final class XsdCompiler {

    private XsdCompiler() {}

    public static void validateXsd(String xsd) throws SAXException, IOException {
        compile(xsd);
    }

    public static Schema compile(String xsd) throws SAXException, IOException {
        return newSchemaFactory().newSchema(new StreamSource(new StringReader(xsd)));
    }

    public static void validate(Schema schema, String xml) throws SAXException, IOException {
        Validator validator = schema.newValidator();
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    public static void validatePayload(String xsd, String xml) throws SAXException, IOException {
        validate(compile(xsd), xml);
    }

    private static SchemaFactory newSchemaFactory() {
        return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    }
}
