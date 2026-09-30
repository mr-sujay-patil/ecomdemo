package com.ecomdemo.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.CatalogIntegrationTest;
import com.ecomdemo.support.PublishedApiDocs;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * catalog-service publishes its own OpenAPI document (KI-001). The gateway routes
 * {@code /v3/api-docs/catalog} here; until KI-001 this answered 401 and the catalogue was documented
 * nowhere.
 */
@DisplayName("catalog-service API documentation")
class ApiDocsIT extends CatalogIntegrationTest {

    @Test
    @DisplayName("an anonymous caller gets the catalogue's OpenAPI document")
    void publishesItsOwnDocument() {
        ResponseEntity<String> response = anonymous.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PublishedApiDocs.assertDocuments(response.getBody(), "EcomDemo Catalog API",
                List.of("/api/products", "/api/products/{id}", "/api/products/search"));
    }

    /**
     * Moved here from ecomdemo-app's {@code OpenApiDocumentationTest}, where it had to assert the
     * schema's ABSENCE after Phase 21. The schema is now named after catalog-service's own record,
     * {@code ProductRequest}; {@code ProductWrite} was the app's proxy copy of it.
     */
    @Test
    @DisplayName("the write schema carries its validation constraints and examples")
    void theWriteSchemaCarriesItsConstraints() {
        JsonNode spec = PublishedApiDocs.assertDocuments(
                anonymous.getForObject("/v3/api-docs", String.class), "EcomDemo Catalog API", List.of());
        JsonNode product = spec.path("components").path("schemas").path("ProductRequest");

        assertThat(product.path("required").valueStream().map(node -> node.asString("")).toList())
                .contains("name", "price", "stockQuantity");
        assertThat(product.path("properties").path("name").path("maxLength").asInt(0)).isEqualTo(255);
        assertThat(product.path("properties").path("description").path("maxLength").asInt(0)).isEqualTo(1000);
        assertThat(product.path("properties").path("price").path("minimum").asDouble(0)).isEqualTo(0.01);
        product.path("properties").properties().forEach(property ->
                assertThat(property.getValue().has("example"))
                        .as("ProductRequest.%s carries an example", property.getKey())
                        .isTrue());
    }
}
