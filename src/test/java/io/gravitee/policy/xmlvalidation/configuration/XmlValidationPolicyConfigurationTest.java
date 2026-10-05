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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class XmlValidationPolicyConfigurationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void shouldDeserializeLegacyConfigWithoutSchemaSourceAsInline() throws IOException {
        XmlValidationPolicyConfiguration configuration = readConfiguration("configuration-legacy-inline.json");

        assertThat(configuration.getSchemaSource()).isEqualTo(SchemaSource.INLINE);
        assertThat(configuration.getXsdSchema()).isEqualTo("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        assertThat(configuration.getErrorMessage()).isEqualTo("validation/internal");
    }

    @Test
    void shouldDeserializeRegistryConfig() throws IOException {
        XmlValidationPolicyConfiguration configuration = readConfiguration("configuration-registry.json");

        assertThat(configuration.getSchemaSource()).isEqualTo(SchemaSource.REGISTRY);
        assertThat(configuration.getRegistryResource()).isEqualTo("solace-registry");
        assertThat(configuration.getGroupId()).isEqualTo("faa-swim");
        assertThat(configuration.getArtifactId()).isEqualTo("fixm-core");
        assertThat(configuration.getVersion()).isEqualTo("4.2.0");
        assertThat(configuration.getXsdSchema()).isNull();
    }

    @Test
    void shouldDeserializeInlineConfig() throws IOException {
        XmlValidationPolicyConfiguration configuration = readConfiguration("configuration-inline.json");

        assertThat(configuration.getSchemaSource()).isEqualTo(SchemaSource.INLINE);
        assertThat(configuration.getXsdSchema()).isEqualTo("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
    }

    private XmlValidationPolicyConfiguration readConfiguration(String resourceName) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(resourceName)) {
            return objectMapper.readValue(inputStream, XmlValidationPolicyConfiguration.class);
        }
    }
}
