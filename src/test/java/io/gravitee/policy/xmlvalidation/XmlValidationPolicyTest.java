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
package io.gravitee.policy.xmlvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainRequest;
import io.gravitee.policy.xmlvalidation.configuration.XmlValidationPolicyConfiguration;
import io.gravitee.policy.xmlvalidation.configuration.schema.SchemaSource;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.schema_registry.api.ArtifactSchemaLookup;
import io.gravitee.resource.schema_registry.api.SchemaClosureLimitExceededException;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class XmlValidationPolicyTest {

    private static final String XSD = """
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

    @Mock
    private HttpPlainExecutionContext ctx;

    @Mock
    private HttpPlainRequest request;

    @BeforeEach
    void useTrampolineScheduler() {
        // Keep validation off the computation pool so TestObserver completes deterministically.
        io.reactivex.rxjava3.plugins.RxJavaPlugins.setComputationSchedulerHandler(s -> Schedulers.trampoline());
    }

    @AfterEach
    void resetSchedulers() {
        io.reactivex.rxjava3.plugins.RxJavaPlugins.reset();
    }

    @Test
    void shouldRejectEmptyRequestBodyOnV4() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setXsdSchema(XSD);

        when(ctx.request()).thenReturn(request);
        Metrics metrics = mock(Metrics.class);
        when(ctx.metrics()).thenReturn(metrics);
        when(request.bodyOrEmpty()).thenReturn(Single.just(Buffer.buffer()));
        when(ctx.interruptWith(any())).thenReturn(Completable.complete());

        new XmlValidationPolicy(configuration).onRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        ArgumentCaptor<ExecutionFailure> failureCaptor = ArgumentCaptor.forClass(ExecutionFailure.class);
        verify(ctx).interruptWith(failureCaptor.capture());
        assertThat(failureCaptor.getValue().statusCode()).isEqualTo(400);
        assertThat(failureCaptor.getValue().message()).contains("XML payload is empty");
    }

    @Test
    void shouldSurfaceArtifactsLimitMessageToClient() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setSchemaSource(SchemaSource.REGISTRY);
        configuration.setRegistryResource("schema-registry");
        configuration.setGroupId("g");
        configuration.setArtifactId("a");
        configuration.setVersion("1");

        ArtifactSchemaLookup lookup = mock(ArtifactSchemaLookup.class);
        when(lookup.isReady()).thenReturn(true);
        when(lookup.getArtifactSchema("g", "a", "1", false)).thenReturn(
            Maybe.error(
                new SchemaClosureLimitExceededException(
                    "Max artifacts in closure exceeded (1)",
                    SchemaClosureLimitExceededException.ClosureLimitKind.ARTIFACTS
                )
            )
        );

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);
        when(ctx.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(ctx.request()).thenReturn(request);
        when(request.bodyOrEmpty()).thenReturn(Single.just(Buffer.buffer("<order/>")));
        when(ctx.metrics()).thenReturn(mock(Metrics.class));
        when(ctx.interruptWith(any())).thenReturn(Completable.complete());

        new XmlValidationPolicy(configuration).onRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        ArgumentCaptor<ExecutionFailure> failureCaptor = ArgumentCaptor.forClass(ExecutionFailure.class);
        verify(ctx).interruptWith(failureCaptor.capture());
        ExecutionFailure failure = failureCaptor.getValue();
        assertThat(failure.key()).isEqualTo(XmlValidationPolicy.XML_VALIDATION_SCHEMA_CLOSURE_LIMIT_EXCEEDED);
        assertThat(failure.message()).contains("Schema closure exceeds the maximum artifacts limit");
        assertThat(failure.message()).doesNotContain("Max artifacts");
        assertThat(failure.message()).doesNotContain("(1)");
    }

    @Test
    void shouldSurfaceBytesLimitMessageToClient() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setSchemaSource(SchemaSource.REGISTRY);
        configuration.setRegistryResource("schema-registry");
        configuration.setGroupId("g");
        configuration.setArtifactId("a");
        configuration.setVersion("1");

        ArtifactSchemaLookup lookup = mock(ArtifactSchemaLookup.class);
        when(lookup.isReady()).thenReturn(true);
        when(lookup.getArtifactSchema("g", "a", "1", false)).thenReturn(
            Maybe.error(
                new SchemaClosureLimitExceededException(
                    "Schema artifact content exceeds maxClosureBytes (16) for g|a|1",
                    SchemaClosureLimitExceededException.ClosureLimitKind.BYTES
                )
            )
        );

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);
        when(ctx.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(ctx.request()).thenReturn(request);
        when(request.bodyOrEmpty()).thenReturn(Single.just(Buffer.buffer("<order/>")));
        when(ctx.metrics()).thenReturn(mock(Metrics.class));
        when(ctx.interruptWith(any())).thenReturn(Completable.complete());

        new XmlValidationPolicy(configuration).onRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        ArgumentCaptor<ExecutionFailure> failureCaptor = ArgumentCaptor.forClass(ExecutionFailure.class);
        verify(ctx).interruptWith(failureCaptor.capture());
        ExecutionFailure failure = failureCaptor.getValue();
        assertThat(failure.key()).isEqualTo(XmlValidationPolicy.XML_VALIDATION_SCHEMA_CLOSURE_LIMIT_EXCEEDED);
        assertThat(failure.message()).contains("Schema closure exceeds the maximum bytes limit");
        assertThat(failure.message()).doesNotContain("maxClosureBytes");
        assertThat(failure.message()).doesNotContain("g|a|1");
    }

    @Test
    void shouldSurfaceDepthLimitMessageToClient() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setSchemaSource(SchemaSource.REGISTRY);
        configuration.setRegistryResource("schema-registry");
        configuration.setGroupId("g");
        configuration.setArtifactId("a");
        configuration.setVersion("1");

        ArtifactSchemaLookup lookup = mock(ArtifactSchemaLookup.class);
        when(lookup.isReady()).thenReturn(true);
        when(lookup.getArtifactSchema("g", "a", "1", false)).thenReturn(
            Maybe.error(
                new SchemaClosureLimitExceededException(
                    "Max import depth exceeded (10)",
                    SchemaClosureLimitExceededException.ClosureLimitKind.IMPORT_DEPTH
                )
            )
        );

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);
        when(ctx.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(ctx.request()).thenReturn(request);
        when(request.bodyOrEmpty()).thenReturn(Single.just(Buffer.buffer("<order/>")));
        when(ctx.metrics()).thenReturn(mock(Metrics.class));
        when(ctx.interruptWith(any())).thenReturn(Completable.complete());

        new XmlValidationPolicy(configuration).onRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        ArgumentCaptor<ExecutionFailure> failureCaptor = ArgumentCaptor.forClass(ExecutionFailure.class);
        verify(ctx).interruptWith(failureCaptor.capture());
        ExecutionFailure failure = failureCaptor.getValue();
        assertThat(failure.key()).isEqualTo(XmlValidationPolicy.XML_VALIDATION_SCHEMA_CLOSURE_LIMIT_EXCEEDED);
        assertThat(failure.message()).contains("Schema closure exceeds the maximum import depth limit");
        assertThat(failure.message()).doesNotContain("Max import depth");
        assertThat(failure.message()).doesNotContain("(10)");
    }

    @Test
    void shouldSurfaceGenericClosureLimitMessageWhenUnrecognized() {
        XmlValidationPolicyConfiguration configuration = new XmlValidationPolicyConfiguration();
        configuration.setSchemaSource(SchemaSource.REGISTRY);
        configuration.setRegistryResource("schema-registry");
        configuration.setGroupId("g");
        configuration.setArtifactId("a");
        configuration.setVersion("1");

        ArtifactSchemaLookup lookup = mock(ArtifactSchemaLookup.class);
        when(lookup.isReady()).thenReturn(true);
        when(lookup.getArtifactSchema("g", "a", "1", false)).thenReturn(
            Maybe.error(new SchemaClosureLimitExceededException("Some future unknown limit"))
        );

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(resourceManager.getResource("schema-registry", ArtifactSchemaLookup.class)).thenReturn(lookup);
        when(ctx.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(ctx.request()).thenReturn(request);
        when(request.bodyOrEmpty()).thenReturn(Single.just(Buffer.buffer("<order/>")));
        when(ctx.metrics()).thenReturn(mock(Metrics.class));
        when(ctx.interruptWith(any())).thenReturn(Completable.complete());

        new XmlValidationPolicy(configuration).onRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        ArgumentCaptor<ExecutionFailure> failureCaptor = ArgumentCaptor.forClass(ExecutionFailure.class);
        verify(ctx).interruptWith(failureCaptor.capture());
        ExecutionFailure failure = failureCaptor.getValue();
        assertThat(failure.key()).isEqualTo(XmlValidationPolicy.XML_VALIDATION_SCHEMA_CLOSURE_LIMIT_EXCEEDED);
        assertThat(failure.message()).contains("Schema closure exceeds configured limits");
    }
}
