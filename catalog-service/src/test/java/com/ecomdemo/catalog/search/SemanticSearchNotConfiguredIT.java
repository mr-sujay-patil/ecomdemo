package com.ecomdemo.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.shared.ApiError;
import com.ecomdemo.support.CatalogIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The DEFAULT configuration, AI_EMBEDDING_PROVIDER unset: the service starts (this context is that
 * start), search and the backfill say how to turn them on, and products are still written - and
 * still announced, so nothing is lost for the index that is built later.
 */
@DisplayName("Semantic search with AI_EMBEDDING_PROVIDER unset")
class SemanticSearchNotConfiguredIT extends CatalogIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProductIndexer indexer;

    @Test
    @DisplayName("search and the backfill answer 503 with the setup steps")
    void searchAndBackfillSayHowToConfigure() {
        ResponseEntity<ApiError> search = rest.getForEntity("/api/products/search?q=laptop", ApiError.class);
        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(search.getBody().message()).contains("not configured").contains("AI_EMBEDDING_PROVIDER");
        assertThat(search.getHeaders().getFirst("Retry-After")).isEqualTo("300");

        ResponseEntity<ApiError> backfill =
                rest.postForEntity("/api/products/embeddings/backfill", null, ApiError.class);
        assertThat(backfill.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM batch_job_execution", Integer.class))
                .as("no job was started only to fail")
                .isZero();
    }

    @Test
    @DisplayName("writes still work and are still announced; the indexer acknowledges without a model")
    void writesAreUnaffected() {
        ResponseEntity<ProductResponse> created = rest.postForEntity("/api/products",
                new ProductRequest("Unindexed Lamp", "A desk lamp", new BigDecimal("999.00"), 1, "LIGHTING"),
                ProductResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long id = created.getBody().id();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'ProductChanged' AND aggregate_id = ?",
                Integer.class, id.toString())).isEqualTo(1);

        indexer.refresh(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_embedding", Integer.class)).isZero();

        rest.delete("/api/products/" + id);
    }
}
