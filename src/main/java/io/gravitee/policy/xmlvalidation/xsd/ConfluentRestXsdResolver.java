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
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Fetches an XSD from a Confluent-compatible Schema Registry REST API using a plain
 * blocking {@link HttpClient} (no Vert.x involved).
 *
 * <p>The blocking HTTP call is wrapped in a {@link Single} and subscribed on a
 * dedicated cached thread pool that is completely independent of Vert.x's scheduler,
 * preventing any interaction with Vert.x's ContextScheduler or sequential task queues.
 *
 * <p>Endpoint called:
 * <pre>GET {registryUrl}/subjects/{subject}/versions/latest</pre>
 *
 * Response JSON format (Confluent SR compatible):
 * <pre>{"subject":"order-schema","version":1,"id":1,"schema":"&lt;xs:schema ...&gt;"}</pre>
 */
public class ConfluentRestXsdResolver implements XsdResolver {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    /** Dedicated non-Vert.x thread pool — ensures blocking HTTP is never on a Vert.x thread. */
    private static final io.reactivex.rxjava3.core.Scheduler BLOCKING_SCHEDULER =
        Schedulers.from(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "xml-schema-fetch");
            t.setDaemon(true);
            return t;
        }));

    private final String registryUrl;
    private final String schemaSubject;

    public ConfluentRestXsdResolver(String registryUrl, String schemaSubject) {
        this.registryUrl = registryUrl.replaceAll("/$", ""); // strip trailing slash
        this.schemaSubject = schemaSubject;
    }

    @Override
    public Single<String> resolveXsd(ExecutionContext executionContext) {
        String url = registryUrl + "/subjects/" + schemaSubject + "/versions/latest";
        return Single.<String>fromCallable(() -> fetchSchema(url))
            .subscribeOn(BLOCKING_SCHEDULER);
    }

    private String fetchSchema(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.schemaregistry.v1+json, application/json")
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 404) {
                throw new XmlValidationException("Schema not found in registry: " + schemaSubject);
            }
            if (status != 200) {
                throw new XmlValidationException(
                    "Registry returned HTTP " + status + " for subject: " + schemaSubject
                );
            }

            return extractSchema(response.body());
        } catch (XmlValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new XmlValidationException(
                "Unable to fetch schema from registry [" + url + "]: " + e.getMessage()
            );
        }
    }

    /**
     * Extracts the {@code "schema"} field value from the Confluent SR JSON response.
     * Uses simple string parsing to avoid requiring Jackson at compile time.
     *
     * Example response:
     * {"subject":"order-schema","version":1,"id":1,"schema":"<xs:schema ...>"}
     */
    static String extractSchema(String json) {
        // Find "schema": and extract the string value after it
        int keyIndex = json.indexOf("\"schema\"");
        if (keyIndex == -1) {
            throw new XmlValidationException("Response missing 'schema' field: " + json);
        }
        int colonIndex = json.indexOf(':', keyIndex);
        int quoteStart = json.indexOf('"', colonIndex + 1);
        if (quoteStart == -1) {
            throw new XmlValidationException("Malformed 'schema' field in registry response");
        }

        // Walk the string respecting escape sequences to find the closing quote
        StringBuilder sb = new StringBuilder();
        int i = quoteStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> sb.append(next);
                }
                i += 2;
            } else if (c == '"') {
                break; // closing quote
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
