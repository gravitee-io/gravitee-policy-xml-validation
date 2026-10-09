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

import io.gravitee.policy.xmlvalidation.schema.CompiledXsdSchema;
import io.gravitee.policy.xmlvalidation.schema.SecureXml;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.Validator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

public final class XmlPayloadValidator {

    /**
     * Configured once (a ServiceLoader lookup) and reused. The factory is only mutated during
     * initialization; afterward {@code newSAXParser()} is the sole call, which returns a fresh
     * parser per invocation and is thread-safe on the JDK's default Xerces implementation. This
     * avoids paying the ServiceLoader lookup cost on every validation.
     */
    private static volatile SAXParserFactory saxParserFactory;
    private static final ReentrantLock FACTORY_LOCK = new ReentrantLock();

    /**
     * Thread-local SAXParser pool avoids the allocation overhead of {@code newSAXParser()} on every
     * validation (~0.5–1ms). Each thread gets its own parser; {@link SAXParser#reset()} is called
     * before each use to clear internal state.
     */
    private static final ThreadLocal<SAXParser> PARSER_POOL = ThreadLocal.withInitial(() -> {
        try {
            return newParser();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SAXParser", e);
        }
    });

    private XmlPayloadValidator() {}

    public static XmlValidationResult validate(CompiledXsdSchema compiledXsdSchema, String xml) {
        if (xml == null || xml.isBlank()) {
            return XmlValidationResult.failure(List.of(new XmlValidationViolation(0, 0, null, "XML payload is empty")));
        }

        Validator validator = compiledXsdSchema.schema().newValidator();
        XmlPathTracker pathTracker = new XmlPathTracker();
        CollectingErrorHandler errorHandler = new CollectingErrorHandler(pathTracker);
        validator.setErrorHandler(errorHandler);
        setLocaleRoot(validator);

        try {
            SAXParser parser = PARSER_POOL.get();
            parser.reset();
            XMLReader xmlReader = parser.getXMLReader();
            setLocaleRoot(xmlReader);
            pathTracker.setParent(xmlReader);
            validator.validate(new SAXSource(pathTracker, new InputSource(new StringReader(xml))));
        } catch (SAXException ex) {
            // Xerces already reported via ErrorHandler; only add if nothing was collected.
            if (errorHandler.errors.isEmpty()) {
                if (ex instanceof SAXParseException saxParseException) {
                    errorHandler.errors.add(XmlValidationViolation.from(saxParseException, pathTracker.currentPath()));
                } else {
                    errorHandler.errors.add(new XmlValidationViolation(0, 0, null, ex.getMessage()));
                }
            }
        } catch (Exception ex) {
            return XmlValidationResult.failure(List.of(new XmlValidationViolation(0, 0, null, ex.getMessage())));
        }

        if (errorHandler.errors.isEmpty()) {
            return XmlValidationResult.success();
        }
        return XmlValidationResult.failure(dedupe(errorHandler.errors));
    }

    /**
     * Drop only exact duplicates ({@code line:column:code:message}) and the redundant Xerces
     * {@code cvc-type.*} companion of a more specific facet error at the same location.
     * Distinct messages that share a code (several attributes on one start tag) are kept.
     */
    static List<XmlValidationViolation> dedupe(List<XmlValidationViolation> errors) {
        Map<String, List<XmlValidationViolation>> byLocation = new LinkedHashMap<>();
        for (XmlValidationViolation violation : errors) {
            byLocation.computeIfAbsent(violation.line() + ":" + violation.column(), key -> new ArrayList<>()).add(violation);
        }
        List<XmlValidationViolation> kept = new ArrayList<>();
        for (List<XmlValidationViolation> atLocation : byLocation.values()) {
            boolean hasSpecific = atLocation.stream().anyMatch(violation -> !isGenericTypeCode(violation.code()));
            Map<String, XmlValidationViolation> byIdentity = new LinkedHashMap<>();
            for (XmlValidationViolation violation : atLocation) {
                if (hasSpecific && isGenericTypeCode(violation.code())) {
                    continue;
                }
                String identity =
                    (violation.code() == null ? "" : violation.code()) + "\0" + (violation.message() == null ? "" : violation.message());
                XmlValidationViolation existing = byIdentity.get(identity);
                if (existing == null || isRicher(violation, existing)) {
                    byIdentity.put(identity, violation);
                }
            }
            kept.addAll(byIdentity.values());
        }
        return List.copyOf(kept);
    }

    private static boolean isRicher(XmlValidationViolation candidate, XmlValidationViolation existing) {
        if ((candidate.element() != null) != (existing.element() != null)) {
            return candidate.element() != null;
        }
        return candidate.path() != null && existing.path() == null;
    }

    private static boolean isGenericTypeCode(String code) {
        return code != null && code.toLowerCase(Locale.ROOT).startsWith("cvc-type.");
    }

    private static SAXParserFactory saxParserFactory() {
        SAXParserFactory factory = saxParserFactory;
        if (factory != null) {
            return factory;
        }
        FACTORY_LOCK.lock();
        try {
            factory = saxParserFactory;
            if (factory == null) {
                saxParserFactory = factory = SecureXml.newSaxParserFactory();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SAXParserFactory", e);
        } finally {
            FACTORY_LOCK.unlock();
        }
        return factory;
    }

    private static SAXParser newParser() throws ParserConfigurationException, SAXException {
        return saxParserFactory().newSAXParser();
    }

    private static void setLocaleRoot(Object xmlComponent) {
        try {
            if (xmlComponent instanceof Validator validator) {
                validator.setProperty("http://apache.org/xml/properties/locale", Locale.ROOT);
            } else if (xmlComponent instanceof XMLReader xmlReader) {
                xmlReader.setProperty("http://apache.org/xml/properties/locale", Locale.ROOT);
            }
        } catch (SAXNotRecognizedException | SAXNotSupportedException ignored) {
            // Optional; not all Xerces/XMLReader implementations expose this property.
        }
    }

    private static final class CollectingErrorHandler implements ErrorHandler {

        private static final int MAX_ERRORS = 20;
        private final List<XmlValidationViolation> errors = new ArrayList<>();
        private final XmlPathTracker pathTracker;

        private CollectingErrorHandler(XmlPathTracker pathTracker) {
            this.pathTracker = pathTracker;
        }

        @Override
        public void warning(SAXParseException exception) {
            // Schema warnings are ignored; only error/fatalError fail validation.
        }

        @Override
        public void error(SAXParseException exception) {
            add(exception);
        }

        @Override
        public void fatalError(SAXParseException exception) {
            add(exception);
        }

        private void add(SAXParseException exception) {
            if (errors.size() < MAX_ERRORS) {
                errors.add(XmlValidationViolation.from(exception, pathTracker.currentPath()));
            }
        }
    }
}
