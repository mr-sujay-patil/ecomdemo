package com.ecomdemo.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.shared.ApiError;
import com.ecomdemo.support.CatalogIntegrationTest;
import com.ecomdemo.support.ConceptEmbeddingModel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The search, the backfill and the indexer against a real PostgreSQL with pgvector, using
 * {@link ConceptEmbeddingModel} in place of a real model. What is proven is the machinery: that the
 * vectors are stored, the HNSW-indexed cosine query ranks them, the filters run inside it, the
 * threshold cuts, and the rows follow the products. How GOOD a real model's ranking is, is the smoke
 * test's question.
 */
@DisplayName("Semantic search: GET /api/products/search, the backfill and the index")
@TestPropertySource(properties = "ecomdemo.search.min-similarity=0.3")
class SemanticSearchApiIT extends CatalogIntegrationTest {

    /** The one query no keyword search can answer against the seed: it shares no word with the sleeve. */
    private static final String COMMUTE_QUERY = "protect my computer while commuting";

    @TestConfiguration
    static class Model {
        @Bean
        ConceptEmbeddingModel conceptEmbeddingModel() {
            return new ConceptEmbeddingModel();
        }
    }

    @Autowired
    private ConceptEmbeddingModel model;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProductIndexer indexer;

    @BeforeEach
    void indexTheCatalogue() {
        model.recover();
        BackfillStatus finished = runBackfill();
        assertThat(finished.status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("the backfill starts at once (202), embeds every product and says so")
    void backfillIndexesEverything() {
        BackfillStatus finished = runBackfill();

        assertThat(finished.read()).isEqualTo(finished.written()).isEqualTo(finished.totalProducts());
        assertThat(finished.indexedProducts()).isEqualTo(finished.totalProducts()).isGreaterThanOrEqualTo(10);
        assertThat(finished.failure()).isNull();
    }

    @Test
    @DisplayName("finds the laptop sleeve for a query sharing no word with it - which keyword search misses")
    void findsByMeaningWhatKeywordsMiss() {
        ProductSearchResponse response = search("q=" + COMMUTE_QUERY);

        assertThat(response.results()).isNotEmpty();
        assertThat(response.results().getFirst().product().name()).isEqualTo("Laptop Sleeve 16\"");
        assertThat(response.results().getFirst().similarity()).isGreaterThan(0.9);
        assertThat(response.results()).extracting(hit -> hit.similarity())
                .as("best first")
                .isSortedAccordingTo((a, b) -> Double.compare(b, a));

        // The baseline: the same words, as a keyword search over name and description.
        List<String> words = Arrays.stream(COMMUTE_QUERY.split(" ")).filter(w -> w.length() > 3).toList();
        for (String word : words) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM product WHERE name = 'Laptop Sleeve 16\"' "
                            + "AND (name ILIKE ? OR description ILIKE ?)",
                    Integer.class, "%" + word + "%", "%" + word + "%"))
                    .as("the keyword '%s' does not appear in the sleeve's text", word)
                    .isZero();
        }
    }

    @Test
    @DisplayName("a query about nothing in the shop returns nothing, rather than the least-bad product")
    void belowTheThresholdIsNotAMatch() {
        assertThat(search("q=a garden hose for the roses").results()).isEmpty();
    }

    @Test
    @DisplayName("filters run inside the vector query: category (any case) and a price range")
    void filtersNarrowTheMatches() {
        List<String> unfiltered = names(search("q=" + COMMUTE_QUERY));
        assertThat(unfiltered).contains("Laptop Sleeve 16\"", "Laptop Stand");

        // The stand costs 2199, the sleeve 1799.
        assertThat(names(search("q=" + COMMUTE_QUERY + "&maxPrice=2000"))).containsExactly("Laptop Sleeve 16\"");
        assertThat(names(search("q=" + COMMUTE_QUERY + "&minPrice=2000"))).containsExactly("Laptop Stand");
        assertThat(names(search("q=" + COMMUTE_QUERY + "&category=accessories"))).containsAll(unfiltered);
        assertThat(search("q=" + COMMUTE_QUERY + "&category=AUDIO").results()).isEmpty();
    }

    @Test
    @DisplayName("results are the catalogue's current rows, with stock, in the index's order")
    void resultsAreCurrentProducts() {
        ProductSearchHit top = search("q=" + COMMUTE_QUERY + "&limit=1").results().getFirst();

        assertThat(top.product().id()).isEqualTo(10L);
        assertThat(top.product().price()).isEqualByComparingTo("1799.00");
        assertThat(top.product().category()).isEqualTo("ACCESSORIES");
    }

    @Test
    @DisplayName("the indexer re-embeds an edited product and forgets a deleted one; the FK removes it too")
    void indexFollowsTheProduct() {
        Long id = rest.postForEntity("/api/products",
                new ProductRequest("Studio Cans", "Closed-back headphones for mixing music", new BigDecimal("9999.00"),
                        3, "AUDIO"),
                ProductResponse.class).getBody().id();
        assertThat(outboxEventsFor(id)).as("the create was announced in its transaction").isEqualTo(1);

        indexer.refresh(id);
        assertThat(embeddedText(id)).contains("Studio Cans").contains("mixing music");
        assertThat(names(search("q=something quiet to listen with"))).contains("Studio Cans");

        rest.put("/api/products/" + id,
                new ProductRequest("Studio Cans", "Water-resistant padded bag for a laptop", new BigDecimal("9999.00"),
                        3, "AUDIO"));
        assertThat(outboxEventsFor(id)).isEqualTo(2);
        indexer.refresh(id);
        assertThat(embeddedText(id)).contains("padded bag").doesNotContain("mixing music");

        rest.delete("/api/products/" + id);
        assertThat(embeddingRows(id)).as("V4's ON DELETE CASCADE, before any event is handled").isZero();
        indexer.refresh(id);
        assertThat(embeddingRows(id)).isZero();
    }

    @Test
    @DisplayName("a model outage is a 503 with Retry-After, not an empty result")
    void modelOutageIsServiceUnavailable() {
        model.failWith(new IllegalStateException("connection refused"));

        ResponseEntity<ApiError> response =
                rest.getForEntity("/api/products/search?q=" + COMMUTE_QUERY, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().message()).contains("embedding model did not answer");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        model.recover();
    }

    @Test
    @DisplayName("a backfill during an outage FAILS and says why; the index keeps what it had")
    void backfillFailureIsReported() {
        long indexedBefore = embeddingRowsTotal();
        model.failWith(new IllegalStateException("model unavailable"));

        BackfillStatus failed = runBackfill();

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failure()).contains("model unavailable");
        assertThat(embeddingRowsTotal()).isEqualTo(indexedBefore);
        model.recover();
    }

    @Test
    @DisplayName("bad requests are 400s and an unknown run is a 404")
    void validation() {
        assertThat(rest.getForEntity("/api/products/search?q= ", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/products/search", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/products/search?q=mouse&limit=50", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/products/search?q=mouse&maxPrice=-1", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/products/embeddings/backfill/999999", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("needs a token, like everything on this service")
    void requiresAToken() {
        assertThat(anonymous.getForEntity("/api/products/search?q=mouse", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private BackfillStatus runBackfill() {
        ResponseEntity<BackfillStatus> started =
                rest.postForEntity("/api/products/embeddings/backfill", null, BackfillStatus.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long id = started.getBody().executionId();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        BackfillStatus status = started.getBody();
        while (!List.of("COMPLETED", "FAILED").contains(status.status()) && Instant.now().isBefore(deadline)) {
            sleep();
            status = rest.getForObject("/api/products/embeddings/backfill/" + id, BackfillStatus.class);
        }
        return status;
    }

    private ProductSearchResponse search(String query) {
        ResponseEntity<ProductSearchResponse> response =
                rest.getForEntity("/api/products/search?" + query, ProductSearchResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static List<String> names(ProductSearchResponse response) {
        return response.results().stream().map(hit -> hit.product().name()).toList();
    }

    private int outboxEventsFor(Long id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'ProductChanged' AND aggregate_id = ?",
                Integer.class, id.toString());
    }

    private String embeddedText(Long id) {
        return jdbc.queryForObject("SELECT content FROM product_embedding WHERE id = ?", String.class, id);
    }

    private int embeddingRows(Long id) {
        return jdbc.queryForObject("SELECT count(*) FROM product_embedding WHERE id = ?", Integer.class, id);
    }

    private long embeddingRowsTotal() {
        return jdbc.queryForObject("SELECT count(*) FROM product_embedding", Long.class);
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
