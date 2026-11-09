package com.ecomdemo.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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

    /**
     * Every document's only server: relative, so it resolves to the gateway the document was fetched
     * through, on whatever port that is published.
     */
    public static final String SERVER = "/";

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
        assertThat(servers).as("the gateway is the only server").containsExactly(SERVER);

        assertThat(spec.path("paths").propertyNames())
                .as("the document describes this service's API")
                .containsAll(paths);
        assertResponseSchemasAreExact(spec);
        return spec;
    }

    /**
     * What a response is promised to contain (KI-084, KI-086, KI-087): every property of a schema
     * a response returns is {@code required}, a nullable one included (Jackson writes it, as
     * {@code null}: the key is always there), and a {@code $ref} has no sibling {@code type}.
     * Without {@code required} a generated client types every field as optional; a {@code type}
     * next to a {@code $ref} must hold as well in OpenAPI 3.1, so {@code {$ref, type: "null"}}
     * reads as "null only".
     */
    public static void assertResponseSchemasAreExact(JsonNode spec) {
        JsonNode schemas = spec.path("components").path("schemas");
        Set<String> returned = reachable(schemas, spec.path("paths"), "responses");
        returned.removeAll(reachable(schemas, spec.path("paths"), "requestBody"));
        assertThat(returned).as("the document returns at least one schema").isNotEmpty();

        for (String name : returned) {
            JsonNode schema = schemas.path(name);
            Set<String> required = new TreeSet<>();
            schema.path("required").forEach(entry -> required.add(entry.asString()));
            schema.path("properties").properties().forEach(property -> {
                String where = name + "." + property.getKey();
                JsonNode value = property.getValue();
                assertThat(value.has("$ref") && value.has("type"))
                        .as("%s has no type beside its $ref (use anyOf for a nullable object)", where)
                        .isFalse();
                assertThat(required.contains(property.getKey()))
                        .as("%s is required (its key is always written, as null when it has no value)", where)
                        .isTrue();
            });
        }
    }

    private static boolean nullable(JsonNode property) {
        return property.path("nullable").asBoolean(false)
                || containsNull(property.path("type"))
                || property.path("anyOf").valueStream().anyMatch(option -> containsNull(option.path("type")))
                || property.path("oneOf").valueStream().anyMatch(option -> containsNull(option.path("type")));
    }

    private static boolean containsNull(JsonNode type) {
        return type.isArray()
                ? type.valueStream().anyMatch(entry -> "null".equals(entry.asString()))
                : "null".equals(type.asString(""));
    }

    /** The component schemas reachable from the operations' {@code part} ("responses" or "requestBody"). */
    private static Set<String> reachable(JsonNode schemas, JsonNode paths, String part) {
        Set<String> found = new TreeSet<>();
        Deque<JsonNode> pending = new ArrayDeque<>();
        paths.valueStream().flatMap(JsonNode::valueStream).forEach(operation -> pending.add(operation.path(part)));
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            String ref = node.path("$ref").asString("");
            if (ref.startsWith("#/components/schemas/")) {
                String name = ref.substring("#/components/schemas/".length());
                if (found.add(name)) {
                    pending.add(schemas.path(name));
                }
            }
            if (node.isContainer()) {
                node.valueStream().forEach(pending::add);
            }
        }
        return found;
    }
}
