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

import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.resource.schema_registry.api.ArtifactSchemaBundle;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

public final class XsdSchemaCompiler {

    private static final int MAX_XSD_CONTENT_LENGTH = 10 * 1024 * 1024;

    private static final ThreadLocal<SchemaFactory> SCHEMA_FACTORY = ThreadLocal.withInitial(() -> {
        try {
            return SecureXml.newSchemaFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create secure SchemaFactory", e);
        }
    });

    private XsdSchemaCompiler() {}

    public static CompiledXsdSchema compile(String xsdContent, SchemaSource schemaSource) {
        if (xsdContent == null || xsdContent.isBlank()) {
            throw new XsdSchemaResolutionException("XSD content is empty", schemaSource);
        }
        if (xsdContent.length() > MAX_XSD_CONTENT_LENGTH) {
            throw new XsdSchemaResolutionException(
                "XSD content exceeds maximum allowed size of " + (MAX_XSD_CONTENT_LENGTH / 1024 / 1024) + "MB",
                schemaSource
            );
        }
        try {
            SchemaFactory factory = SCHEMA_FACTORY.get();
            Schema schema = factory.newSchema(new StreamSource(new StringReader(xsdContent)));
            return new CompiledXsdSchema(schema);
        } catch (SAXException ex) {
            throw new XsdSchemaResolutionException("Unable to compile XSD schema: " + ex.getMessage(), schemaSource, ex);
        }
    }

    /**
     * Compile a registry bundle: root XSD plus related documents resolved only from {@code documentsByName}
     * (never from the network).
     */
    public static CompiledXsdSchema compile(ArtifactSchemaBundle bundle) {
        if (bundle == null || bundle.rootContent() == null || bundle.rootContent().length == 0) {
            throw new XsdSchemaResolutionException("XSD content is empty", SchemaSource.REGISTRY);
        }
        long total = bundle.rootContent().length;
        for (byte[] doc : bundle.documentsByName().values()) {
            total += doc.length;
        }
        if (total > MAX_XSD_CONTENT_LENGTH) {
            throw new XsdSchemaResolutionException(
                "XSD closure exceeds maximum allowed size of " + (MAX_XSD_CONTENT_LENGTH / 1024 / 1024) + "MB",
                SchemaSource.REGISTRY
            );
        }
        try {
            Schema schema = CompiledXsdSchemaHolder.schemaForDigest(
                bundle.digest(),
                () -> {
                    try {
                        SchemaFactory factory = SecureXml.newSchemaFactory();
                        factory.setResourceResolver(bundleResolver(bundle.documentsByName()));
                        String root = new String(bundle.rootContent(), StandardCharsets.UTF_8);
                        return factory.newSchema(new StreamSource(new StringReader(root)));
                    } catch (SAXException e) {
                        throw new IllegalStateException(e);
                    }
                }
            );
            return new CompiledXsdSchema(schema);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new XsdSchemaResolutionException(
                "Unable to compile XSD schema: " + cause.getMessage(),
                SchemaSource.REGISTRY,
                null,
                bundle.groupId(),
                bundle.artifactId(),
                bundle.version(),
                cause,
                XsdSchemaResolutionException.FailureKind.COMPILE
            );
        }
    }

    private static LSResourceResolver bundleResolver(Map<String, byte[]> documentsByName) {
        return (type, namespaceURI, publicId, systemId, baseURI) -> {
            if (systemId == null || systemId.isBlank()) {
                return null;
            }
            if (systemId.startsWith("http://") || systemId.startsWith("https://") || systemId.startsWith("file://")) {
                throw new IllegalArgumentException("External schemaLocation is not allowed: " + systemId);
            }
            String basename = systemId;
            int slash = Math.max(systemId.lastIndexOf('/'), systemId.lastIndexOf('\\'));
            if (slash >= 0 && slash < systemId.length() - 1) {
                basename = systemId.substring(slash + 1);
            }
            byte[] bytes = documentsByName.get(basename);
            if (bytes == null) {
                bytes = documentsByName.get(systemId);
            }
            if (bytes == null) {
                return null; // SchemaFactory will fail compile — incomplete closure
            }
            LSInput input = new SimpleLsInput();
            input.setPublicId(publicId);
            input.setSystemId(systemId);
            input.setBaseURI(baseURI);
            input.setByteStream(new ByteArrayInputStream(bytes));
            return input;
        };
    }

    private static final class SimpleLsInput implements LSInput {

        private String publicId;
        private String systemId;
        private String baseURI;
        private java.io.InputStream byteStream;
        private String encoding = "UTF-8";

        @Override
        public java.io.Reader getCharacterStream() {
            return null;
        }

        @Override
        public void setCharacterStream(java.io.Reader characterStream) {}

        @Override
        public java.io.InputStream getByteStream() {
            return byteStream;
        }

        @Override
        public void setByteStream(java.io.InputStream byteStream) {
            this.byteStream = byteStream;
        }

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public void setStringData(String stringData) {}

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public void setSystemId(String systemId) {
            this.systemId = systemId;
        }

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public void setPublicId(String publicId) {
            this.publicId = publicId;
        }

        @Override
        public String getBaseURI() {
            return baseURI;
        }

        @Override
        public void setBaseURI(String baseURI) {
            this.baseURI = baseURI;
        }

        @Override
        public String getEncoding() {
            return encoding;
        }

        @Override
        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {}
    }
}
