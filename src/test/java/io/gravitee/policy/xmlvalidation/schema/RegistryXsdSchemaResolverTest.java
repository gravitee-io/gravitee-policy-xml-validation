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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.policy.xmlvalidation.validation.XmlPayloadValidator;
import io.gravitee.policy.xmlvalidation.validation.XmlValidationResult;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.schema_registry.api.ArtifactSchemaBundle;
import io.gravitee.resource.schema_registry.api.ArtifactSchemaLookup;
import io.reactivex.rxjava3.core.Maybe;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RegistryXsdSchemaResolverTest {

    private static final String XSD =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
          <xs:element name="message" type="xs:string"/>
        </xs:schema>""";

    @Test
    void shouldResolveSchemaFromArtifactLookup() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setSchemaSource(SchemaSource.REGISTRY);
        configuration.setRegistryResource("schema-registry");
        configuration.setGroupId("faa-swim");
        configuration.setArtifactId("fixm-core");
        configuration.setVersion("1");

        ArtifactSchemaBundle bundle = new ArtifactSchemaBundle(
            XSD.getBytes(StandardCharsets.UTF_8),
            Map.of(),
            "digest-1",
            "faa-swim",
            "fixm-core",
            "1"
        );

        ArtifactSchemaLookup lookup = mock(ArtifactSchemaLookup.class);
        when(lookup.isReady()).thenReturn(true);
        when(lookup.getArtifactSchema("faa-swim", "fixm-core", "1", false)).thenReturn(Maybe.just(bundle));

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);

        CompiledXsdSchema compiled = new RegistryXsdSchemaResolver(configuration, resourceManager).resolveReactive().blockingGet();
        XmlValidationResult result = XmlPayloadValidator.validate(compiled, "<message>ok</message>");
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void shouldFailWhenResourceNotReady() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setSchemaSource(SchemaSource.REGISTRY);
        configuration.setRegistryResource("schema-registry");
        configuration.setGroupId("g");
        configuration.setArtifactId("a");
        configuration.setVersion("1");

        ArtifactSchemaLookup lookup = mock(ArtifactSchemaLookup.class);
        when(lookup.isReady()).thenReturn(false);

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);

        new RegistryXsdSchemaResolver(configuration, resourceManager)
            .resolveReactive()
            .test()
            .awaitDone(5, TimeUnit.SECONDS)
            .assertError(error ->
                error instanceof XsdSchemaResolutionException xsre &&
                xsre.getFailureKind() == XsdSchemaResolutionException.FailureKind.NOT_READY
            );
    }

    @Test
    void shouldMapEmptyToNotFound() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setSchemaSource(SchemaSource.REGISTRY);
        configuration.setRegistryResource("schema-registry");
        configuration.setGroupId("g");
        configuration.setArtifactId("a");
        configuration.setVersion("1");

        ArtifactSchemaLookup lookup = mock(ArtifactSchemaLookup.class);
        when(lookup.isReady()).thenReturn(true);
        when(lookup.getArtifactSchema("g", "a", "1", false)).thenReturn(Maybe.empty());

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);

        // observeOn(computation) makes empty→error async; await completion before assert.
        new RegistryXsdSchemaResolver(configuration, resourceManager)
            .resolveReactive()
            .test()
            .awaitDone(5, TimeUnit.SECONDS)
            .assertError(error ->
                error instanceof XsdSchemaResolutionException xsre &&
                xsre.getFailureKind() == XsdSchemaResolutionException.FailureKind.NOT_FOUND
            );
    }
}
