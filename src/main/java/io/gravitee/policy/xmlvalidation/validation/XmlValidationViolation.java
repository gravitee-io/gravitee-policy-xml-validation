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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xml.sax.SAXParseException;

public record XmlValidationViolation(int line, int column, String element, String message, String code, String path) {
    private static final Pattern ELEMENT_PATTERN = Pattern.compile("element '([^']+)'");
    private static final Pattern CODE_PATTERN = Pattern.compile("\\b(cvc-[\\w.\\-]+)\\b", Pattern.CASE_INSENSITIVE);
    /** Xerces anonymous type names often look like {@code #AnonType_<localName>}. */
    private static final Pattern ANON_TYPE_ELEMENT = Pattern.compile("#AnonType_([A-Za-z_][\\w.-]*)");

    public XmlValidationViolation(int line, int column, String element, String message) {
        this(line, column, element, message, extractCode(message), null);
    }

    public XmlValidationViolation(int line, int column, String element, String message, String code) {
        this(line, column, element, message, code, null);
    }

    public static XmlValidationViolation from(SAXParseException exception) {
        return from(exception, null);
    }

    public static XmlValidationViolation from(SAXParseException exception, String path) {
        String message = exception.getMessage();
        // Prefer the live parse path local-name over Xerces message scraping (#AnonType_quantityorder).
        String element = (path != null && !path.isBlank()) ? localName(path) : extractElement(message);
        if (element == null || element.isBlank()) {
            element = extractElement(message);
        }
        return new XmlValidationViolation(
            exception.getLineNumber(),
            exception.getColumnNumber(),
            element,
            message,
            extractCode(message),
            path
        );
    }

    public String formatted() {
        StringBuilder builder = new StringBuilder();
        if (line > 0) {
            builder.append("line ").append(line);
        }
        if (column > 0) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append("column ").append(column);
        }
        if (path != null && !path.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append("path '").append(path).append("'");
        } else if (element != null && !element.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append("field '").append(element).append("'");
        }
        if (!builder.isEmpty()) {
            builder.append(": ");
        }
        builder.append(message != null ? message : "XML validation failed");
        return builder.toString();
    }

    private static String extractElement(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = ELEMENT_PATTERN.matcher(message);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group(1);
        }
        if (lastMatch != null) {
            return lastMatch;
        }
        Matcher anon = ANON_TYPE_ELEMENT.matcher(message);
        if (anon.find()) {
            return anon.group(1);
        }
        return null;
    }

    private static String extractCode(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = CODE_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String localName(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) {
            return path.startsWith("/") ? path.substring(1) : path;
        }
        return path.substring(slash + 1);
    }
}
