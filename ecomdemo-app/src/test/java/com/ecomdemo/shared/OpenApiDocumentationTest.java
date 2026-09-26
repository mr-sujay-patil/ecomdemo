package com.ecomdemo.shared;

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

    /**
     * Every path THIS APPLICATION serves. Written out so a path that drops out of the spec is noticed.
     *
     * <p><strong>Five paths left this list in Phase 21</strong>, and that is the phase working rather
     * than a regression: {@code /api/products}, {@code /api/products/{id}}, {@code /api/auth/login},
     * {@code /api/customers/register} and {@code /api/customers/me} were hand-written proxies to
     * catalog-service and customer-service, kept only so that the split stayed invisible to clients.
     * The gateway routes them now, so those services document their own.
     *
     * <p>A client sees no difference — same URLs, same bodies, on the same port 8080. What changed is
     * that the application no longer claims to own them, and this document is the place that claim was
     * written down.
     */
    private static final List<String> API_PATHS = List.of(
            "/api/cart",
            "/api/cart/items",
            "/api/cart/items/{productId}",
            "/api/orders",
            "/api/orders/{id}",
            "/api/admin/batch/product-import",
            "/api/admin/batch/executions/{id}",
            "/api/admin/batch/executions/{id}/restart");

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
        // ProductWrite left this list in Phase 21 along with the proxy that exposed it. See the
        // note on the test below for where the catalogue's schema now is - and is not.
        List<String> requestSchemas = List.of("AddCartItemRequest", "UpdateCartItemRequest");

        // When / Then
        requestSchemas.forEach(name -> schema(name).path("properties").properties()
                .forEach(property -> assertThat(property.getValue().has("example"))
                        .as("%s.%s carries an example", name, property.getKey())
                        .isTrue()));
    }

    /**
     * ⚠️ THE CATALOGUE'S SCHEMA IS NOW DOCUMENTED NOWHERE, and this test is the evidence.
     *
     * <p>It used to assert that {@code ProductWrite} carried its validation constraints into the
     * document. That schema appeared in THIS application's spec only because the application proxied
     * {@code /api/products}; the gateway routes it now, and {@code ProductWrite} is gone from here.
     *
     * <p>It has not reappeared elsewhere. Of the six modules only {@code ecomdemo-app} declares
     * springdoc, so catalog-service publishes no OpenAPI document of its own and there is no document
     * that describes the catalogue any more. A gateway can aggregate its services' specifications into
     * one, which is the real fix and a piece of work in its own right.
     *
     * <p>So this asserts what is TRUE rather than what we would like: the schema is absent. A test that
     * silently dropped the claim would leave the gap invisible; one that fails would block the phase
     * over documentation. This one fails the day somebody adds it back, which is when this comment
     * needs deleting.
     */
    @Test
    void apiDocs_theCatalogueSchemaIsNoLongerThisApplicationsToDocument() {
        assertThat(schema("ProductWrite").isMissingNode())
                .as("ProductWrite belongs to catalog-service since Phase 21 - if this fails, the "
                        + "catalogue is documented again and the follow-up is resolved")
                .isTrue();
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
    void apiDocs_declaresTheBearerScheme() {
        // Without this the "Authorize" button does not appear in Swagger UI and "Try it out"
        // cannot send a token, which makes the UI useless for everything but browsing.
        JsonNode scheme = spec.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(scheme.isMissingNode()).as("the document declares bearerAuth").isFalse();
        assertThat(scheme.path("type").asString("")).isEqualTo("http");
        assertThat(scheme.path("scheme").asString("")).isEqualTo("bearer");
        // Documentation rather than configuration: it tells a reader, and a code generator, that
        // the opaque string is a JWT.
        assertThat(scheme.path("bearerFormat").asString("")).isEqualTo("JWT");
        assertThat(spec.path("components").path("securitySchemes").has("basicAuth"))
                .as("Basic authentication is gone, so the document must not still advertise it")
                .isFalse();
    }

    @Test
    void apiDocs_marksTheProtectedOperationsAndLeavesThePublicOnesOpen() {
        // EVERY remaining operation requires an account, and that is itself the Phase 21 change:
        // the three genuinely public ones - browsing, registering and logging in - were the proxied
        // paths, and they are the gateway's now. What is left in this application is a shopper's cart,
        // their orders and an administrator's batch jobs, none of which a stranger may touch.
        //
        // The public/private distinction did not disappear with them; it moved. EdgeSecurityIT in
        // gateway-service asserts that anonymous browsing is permitted and an anonymous write is not.
        assertThat(requiresAuth("/api/cart", "get")).as("viewing the cart").isTrue();
        assertThat(requiresAuth("/api/orders", "post")).as("checking out").isTrue();
        assertThat(requiresAuth("/api/orders/{id}", "get")).as("reading one order").isTrue();
        assertThat(requiresAuth("/api/admin/batch/product-import", "post"))
                .as("an administrator's import")
                .isTrue();
    }

    private boolean requiresAuth(String path, String method) {
        return !spec.path("paths").path(path).path(method).path("security").isEmpty();
    }
}
