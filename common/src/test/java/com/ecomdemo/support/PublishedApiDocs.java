package com.ecomdemo.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every documented service's {@code /v3/api-docs} must say, checked the same way in each (KI-001).
 *
 * <p>A service's document is read by a browser through the gateway's Swagger UI, never from the
 * service's own port. So beyond "it is an OpenAPI 3 document", two things matter to a reader: the
 * title says WHICH service this is (all six used to be "EcomDemo API"), and the only server is the
 * gateway, so "Try it out" sends its request through the same edge a real client uses.
 */
public final class PublishedApiDocs {

    /** The gateway, the one address a client uses. Every document's only server. */
    public static final String GATEWAY = "http://localhost:8080";

    private PublishedApiDocs() {
    }

    /**
     * Parses {@code body} and asserts it is this service's document.
     *
     * @return the parsed document, for the caller's own service-specific assertions
     */
    public static JsonNode assertDocuments(String body, String title, List<String> paths) {
        assertThat(body).as("the service serves /v3/api-docs").isNotBlank();
        JsonNode spec = JsonMapper.builder().build().readTree(body);

        assertThat(spec.path("openapi").asString("")).as("an OpenAPI 3 document").startsWith("3.");
        assertThat(spec.path("info").path("title").asString("")).isEqualTo(title);
        assertThat(spec.path("info").path("description").asString("")).isNotBlank();

        List<String> servers = spec.path("servers").valueStream()
                .map(server -> server.path("url").asString(""))
                .toList();
        assertThat(servers).as("the gateway is the only server").containsExactly(GATEWAY);

        assertThat(spec.path("paths").propertyNames())
                .as("the document describes this service's API")
                .containsAll(paths);
        return spec;
    }
}
