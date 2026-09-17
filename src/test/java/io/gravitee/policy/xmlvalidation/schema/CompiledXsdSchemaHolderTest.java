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

import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.schema_registry.api.ArtifactSchemaBundle;
import io.gravitee.resource.schema_registry.api.ArtifactSchemaLookup;
import io.gravitee.resource.schema_registry.api.SchemaRegistryUnreachableException;
import io.reactivex.rxjava3.core.Maybe;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompiledXsdSchemaHolderTest {

    private static final String XSD =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
          <xs:element name="message" type="xs:string"/>
        </xs:schema>""";

    @Test
    void shouldLoadRegistrySchemaOnceUnderConcurrentRequests() throws Exception {
        XmlValidationPolicyConfiguration configuration = registryConfig();
        AtomicInteger fetches = new AtomicInteger();
        ArtifactSchemaLookup lookup = readyLookup(fetches, Maybe.just(bundle("digest-concurrent")));

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);

        HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
        when(ctx.getComponent(ResourceManager.class)).thenReturn(resourceManager);

        CompiledXsdSchemaHolder holder = CompiledXsdSchemaHolder.forConfiguration(configuration);

        int callers = 32;
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    holder.ensureReady(ctx).blockingAwait();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        assertThat(failures).isEmpty();
        assertThat(holder.compiledSchema()).isNotNull();
        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void shouldCompileInlineSchemaEagerly() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setXsdSchema(XSD);

        CompiledXsdSchemaHolder holder = CompiledXsdSchemaHolder.forConfiguration(configuration);

        assertThat(holder.compiledSchema()).isNotNull();
    }

    @Test
    void shouldRetryAfterTransientUnreachableFailure() {
        XmlValidationPolicyConfiguration configuration = registryConfig();
        AtomicInteger fetches = new AtomicInteger();
        ArtifactSchemaLookup lookup = mock(ArtifactSchemaLookup.class);
        when(lookup.isReady()).thenReturn(true);
        when(lookup.getArtifactSchema("g", "a", "1", false))
            .thenAnswer(invocation -> {
                int n = fetches.incrementAndGet();
                if (n == 1) {
                    return Maybe.error(new SchemaRegistryUnreachableException("down"));
                }
                return Maybe.just(bundle("digest-retry"));
            });

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);

        HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
        when(ctx.getComponent(ResourceManager.class)).thenReturn(resourceManager);

        CompiledXsdSchemaHolder holder = CompiledXsdSchemaHolder.forConfiguration(configuration);

        holder.ensureReady(ctx).test().awaitDone(5, java.util.concurrent.TimeUnit.SECONDS).assertError(XsdSchemaResolutionException.class);
        holder.ensureReady(ctx).test().awaitDone(5, java.util.concurrent.TimeUnit.SECONDS).assertComplete();
        assertThat(holder.compiledSchema()).isNotNull();
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void shouldPropagateUnreachableToConcurrentCallersWithoutPinningPermanentFailure() throws Exception {
        XmlValidationPolicyConfiguration configuration = registryConfig();
        AtomicInteger fetches = new AtomicInteger();
        ArtifactSchemaLookup lookup = readyLookup(fetches, Maybe.error(new SchemaRegistryUnreachableException("Registry unavailable")));

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);

        HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
        when(ctx.getComponent(ResourceManager.class)).thenReturn(resourceManager);

        CompiledXsdSchemaHolder holder = CompiledXsdSchemaHolder.forConfiguration(configuration);

        int callers = 32;
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> caughtErrors = new CopyOnWriteArrayList<>();

        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    holder.ensureReady(ctx).blockingAwait();
                } catch (Throwable t) {
                    caughtErrors.add(t);
                }
            });
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        assertThat(caughtErrors).hasSize(callers);
        for (Throwable error : caughtErrors) {
            assertThat(error).isInstanceOf(XsdSchemaResolutionException.class);
            assertThat(((XsdSchemaResolutionException) error).getFailureKind())
                .isEqualTo(XsdSchemaResolutionException.FailureKind.UNREACHABLE);
        }
        // Single-flight while the first attempt is in progress; late entrants after clear may retry.
        assertThat(fetches.get()).isBetween(1, callers);
    }

    private static XmlValidationPolicyConfiguration registryConfig() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setSchemaSource(SchemaSource.REGISTRY);
        configuration.setRegistryResource("schema-registry");
        configuration.setGroupId("g");
        configuration.setArtifactId("a");
        configuration.setVersion("1");
        return configuration;
    }

    private static ArtifactSchemaLookup readyLookup(AtomicInteger fetches, Maybe<ArtifactSchemaBundle> response) {
        ArtifactSchemaLookup lookup = mock(ArtifactSchemaLookup.class);
        when(lookup.isReady()).thenReturn(true);
        when(lookup.getArtifactSchema("g", "a", "1", false))
            .thenAnswer(invocation -> {
                fetches.incrementAndGet();
                return response;
            });
        return lookup;
    }

    private static ArtifactSchemaBundle bundle(String digest) {
        return new ArtifactSchemaBundle(XSD.getBytes(StandardCharsets.UTF_8), Map.of(), digest, "g", "a", "1");
    }
}
