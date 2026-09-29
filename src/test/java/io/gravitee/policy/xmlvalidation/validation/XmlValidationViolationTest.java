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

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

class XmlValidationViolationTest {

    @Test
    void shouldExtractLastElementFromValidationMessage() {
        SAXParseException exception = new SAXParseException(
            "cvc-complex-type.2.4.a: Invalid content was found starting with element 'company'. One of '{companies}' is expected.",
            null,
            null,
            4,
            18
        );

        XmlValidationViolation violation = XmlValidationViolation.from(exception);

        assertThat(violation.element()).isEqualTo("company");
        assertThat(violation.code()).isEqualTo("cvc-complex-type.2.4.a");
        assertThat(violation.formatted()).contains("line 4, column 18, field 'company'");
    }

    @Test
    void shouldExtractElementFromTypeMismatchMessage() {
        SAXParseException exception = new SAXParseException(
            "cvc-type.3.1.3: The value 'abc' of element 'employeeNumber' is not valid.",
            null,
            null,
            6,
            29
        );

        XmlValidationViolation violation = XmlValidationViolation.from(exception);

        assertThat(violation.element()).isEqualTo("employeeNumber");
        assertThat(violation.code()).isEqualTo("cvc-type.3.1.3");
        assertThat(violation.formatted()).contains("field 'employeeNumber'");
    }
}
