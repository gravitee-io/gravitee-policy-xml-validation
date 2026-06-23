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

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.policy.XmlValidationException;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.schema_registry.api.Schema;
import io.gravitee.resource.schema_registry.api.SchemaRegistryResource;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ResourceBasedXsdResolver implements XsdResolver {

    private static final Logger log = Logger.getLogger(ResourceBasedXsdResolver.class.getName());

    private final String resourceName;
    private final String schemaMapping;

    public ResourceBasedXsdResolver(String resourceName, String schemaMapping) {
        this.resourceName = resourceName;
        this.schemaMapping = schemaMapping;
    }

    /**
     * Returns a cold {@link Single} that resolves the XSD from the configured
     * {@link SchemaRegistryResource}. No blocking occurs — the resource's own
     * reactive chain is returned directly and will be subscribed to by the caller.
     */
    @Override
    public Single<String> resolveXsd(ExecutionContext executionContext) {
        ResourceManager resourceManager = executionContext.getComponent(ResourceManager.class);
        if (resourceManager == null) {
            return Single.error(new XmlValidationException("Unable to resolve resource manager"));
        }

        SchemaRegistryResource<?> schemaRegistryResource = resourceManager.getResource(resourceName, SchemaRegistryResource.class);
        if (schemaRegistryResource == null) {
            return Single.error(new XmlValidationException("Unable to resolve schema registry resource: " + resourceName));
        }

        return executionContext
            .getTemplateEngine()
            .eval(schemaMapping, String.class)
            .flatMap(schemaKey -> resolveSchema(schemaRegistryResource, schemaKey))
            .switchIfEmpty(Single.error(new XmlValidationException("Unable to resolve schema for mapping: " + schemaMapping)))
            .map(Schema::getContent)
            .doOnError(error -> log.log(Level.SEVERE, "Unable to resolve XSD schema from registry resource " + resourceName, error));
    }

    private Maybe<Schema> resolveSchema(SchemaRegistryResource<?> schemaRegistryResource, String schemaKey) {
        return nullableMaybe(schemaRegistryResource.getSchemaById(schemaKey)).switchIfEmpty(
            nullableMaybe(schemaRegistryResource.getSchema(schemaKey))
        );
    }

    private Maybe<Schema> nullableMaybe(Maybe<Schema> maybe) {
        return maybe != null ? maybe : Maybe.empty();
    }
}
