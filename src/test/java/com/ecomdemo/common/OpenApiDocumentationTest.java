package com.ecomdemo.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards the generated OpenAPI document.
 *
 * <p>{@code scripts/smoke-test.sh} checks the same things against a running application; this
 * checks them inside {@code ./mvnw verify}, so a controller added without annotations fails the
 * build instead of waiting for somebody to run the smoke test.
 *
 * <p>It has to be a full {@code @SpringBootTest}: springdoc contributes {@code /v3/api-docs}
 * through auto-configuration, which a {@code @WebMvcTest} slice does not load. The document is
 * fetched once for the class — building it is the expensive part and every test here only reads
 * it — which is what {@code PER_CLASS} allows: a non-static {@code @BeforeAll} that can use the
 * injected {@link MockMvcTester}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiDocumentationTest {

    /** Every path the API serves. Written out so a path that drops out of the spec is noticed. */
    private static final List<String> API_PATHS = List.of(
            "/api/products",
            "/api/products/{id}",
            "/api/cart",
            "/api/cart/items",
            "/api/cart/items/{productId}",
            "/api/orders",
            "/api/orders/{id}",
            "/api/customers/register",
            "/api/customers/me");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode spec;

    @BeforeAll
    void fetchTheSpec() throws Exception {
        String body = mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString();
        spec = objectMapper.readTree(body);
    }

    static List<String> apiPaths() {
        return API_PATHS;
    }

    @Test
    void apiDocs_whenFetched_describesTheApplication() {
        // Given / When: the document fetched once for this class
        // Then
        assertThat(spec.path("openapi").asString("")).startsWith("3.");
        assertThat(spec.path("info").path("title").asString("")).isEqualTo("EcomDemo API");
        assertThat(spec.path("info").path("version").asString("")).isEqualTo("v1");
        assertThat(spec.path("info").path("description").asString("")).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("apiPaths")
    void apiDocs_forEveryApiPath_documentsIt(String path) {
        assertThat(spec.path("paths").has(path)).as("the spec documents %s", path).isTrue();
    }

    @Test
    void apiDocs_whenFetched_documentsNothingBeyondTheApi() {
        // Given / When
        var documented = spec.path("paths").propertyNames();

        // Then: the H2 console and the Swagger UI resources are not part of the API contract
        assertThat(documented).containsExactlyInAnyOrderElementsOf(API_PATHS);
    }

    @Test
    void apiDocs_everyOperation_hasASummaryAndATag() {
        eachOperation((path, method, operation) -> {
            assertThat(operation.path("summary").asString(""))
                    .as("%s %s has a summary", method, path)
                    .isNotBlank();
            assertThat(operation.path("tags").isEmpty())
                    .as("%s %s is grouped under a tag", method, path)
                    .isFalse();
        });
    }

    @Test
    void apiDocs_everyResponse_hasADescription() {
        eachOperation((path, method, operation) -> operation.path("responses").properties()
                .forEach(response -> assertThat(response.getValue().path("description").asString(""))
                        .as("%s %s -> %s has a description", method, path, response.getKey())
                        .isNotBlank()));
    }

    @Test
    void apiDocs_everyErrorResponse_returnsApiErrorAsJson() {
        eachOperation((path, method, operation) -> operation.path("responses").properties().stream()
                .filter(response -> response.getKey().compareTo("400") >= 0)
                .forEach(response -> {
                    JsonNode json = response.getValue().path("content").path("application/json");
                    assertThat(json.isMissingNode())
                            .as("%s %s -> %s is documented as application/json", method, path, response.getKey())
                            .isFalse();
                    assertThat(json.path("schema").path("$ref").asString(""))
                            .as("%s %s -> %s returns ApiError", method, path, response.getKey())
                            .isEqualTo("#/components/schemas/ApiError");
                }));
    }

    @Test
    void apiDocs_theErrorSchema_matchesTheApiErrorRecord() {
        // Given / When
        JsonNode apiError = schema("ApiError");

        // Then
        assertThat(apiError.path("properties").propertyNames())
                .containsExactlyInAnyOrder("status", "message");
        assertThat(apiError.path("properties").path("status").path("example").asInt(0)).isEqualTo(404);
    }

    @Test
    void apiDocs_everyRequestSchemaProperty_carriesAnExample() {
        // Given: the schemas a client has to fill in by hand
        List<String> requestSchemas =
                List.of("ProductRequest", "AddCartItemRequest", "UpdateCartItemRequest");

        // When / Then
        requestSchemas.forEach(name -> schema(name).path("properties").properties()
                .forEach(property -> assertThat(property.getValue().has("example"))
                        .as("%s.%s carries an example", name, property.getKey())
                        .isTrue()));
    }

    @Test
    void apiDocs_theProductRequestSchema_repeatsTheValidationConstraints() {
        // Given / When: springdoc reads the Bean Validation annotations already on the record,
        // which is why @Schema does not restate them
        JsonNode productRequest = schema("ProductRequest");

        // Then
        assertThat(productRequest.path("required").values())
                .extracting(node -> node.asString(""))
                .containsExactlyInAnyOrder("name", "price", "stockQuantity");
        assertThat(productRequest.path("properties").path("name").path("maxLength").asInt(0))
                .isEqualTo(255);
        assertThat(productRequest.path("properties").path("price").path("minimum").asDouble(0))
                .isEqualTo(0.01);
    }

    private JsonNode schema(String name) {
        return spec.path("components").path("schemas").path(name);
    }

    /** Runs an assertion over every operation of every path. */
    private void eachOperation(OperationAssertion assertion) {
        spec.path("paths")
                .properties()
                .forEach(path -> path.getValue()
                        .properties()
                        .forEach(operation ->
                                assertion.check(path.getKey(), operation.getKey(), operation.getValue())));
    }

    @FunctionalInterface
    private interface OperationAssertion {
        void check(String path, String method, JsonNode operation);
    }

    @Test
    void apiDocs_declaresTheBasicAuthScheme() {
        // Without this the "Authorize" button does not appear in Swagger UI and "Try it out"
        // cannot send credentials, which makes the UI useless for everything but browsing.
        JsonNode scheme = spec.path("components").path("securitySchemes").path("basicAuth");
        assertThat(scheme.isMissingNode()).as("the document declares basicAuth").isFalse();
        assertThat(scheme.path("type").asString("")).isEqualTo("http");
        assertThat(scheme.path("scheme").asString("")).isEqualTo("basic");
    }

    @Test
    void apiDocs_marksTheProtectedOperationsAndLeavesThePublicOnesOpen() {
        // The document doubles as a readable statement of what needs an account, so the two
        // genuinely public operations must NOT carry a security requirement...
        assertThat(requiresAuth("/api/products", "get")).as("browsing is public").isFalse();
        assertThat(requiresAuth("/api/customers/register", "post"))
                .as("registering cannot require an account")
                .isFalse();

        // ...and everything else must.
        assertThat(requiresAuth("/api/products", "post")).as("creating a product").isTrue();
        assertThat(requiresAuth("/api/products/{id}", "delete")).as("deleting a product").isTrue();
        assertThat(requiresAuth("/api/cart", "get")).as("viewing the cart").isTrue();
        assertThat(requiresAuth("/api/orders", "post")).as("checking out").isTrue();
        assertThat(requiresAuth("/api/customers/me", "get")).as("your own profile").isTrue();
    }

    private boolean requiresAuth(String path, String method) {
        return !spec.path("paths").path(path).path(method).path("security").isEmpty();
    }
}
