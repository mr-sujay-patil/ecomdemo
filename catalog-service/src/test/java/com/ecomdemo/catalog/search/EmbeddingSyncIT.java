package com.ecomdemo.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.CatalogIntegrationTest;
import com.ecomdemo.support.ConceptEmbeddingModel;
import com.ecomdemo.support.KafkaContainerConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole path, with nothing called by hand: a product written through the API is announced in
 * the outbox, the relay publishes it to a real Kafka broker, the indexer consumes it and embeds the
 * product, and the search finds it. Then the same for an edit.
 *
 * <p>The one test in this module with a broker, so it turns back on what {@code application-it}
 * turns off: the relay (every 200 ms), topic creation and the listeners.
 */
@DisplayName("Embedding sync end to end: API -> outbox -> Kafka -> indexer -> pgvector -> search")
@Import(KafkaContainerConfig.class)
@TestPropertySource(properties = {
    "ecomdemo.outbox.poll-delay=200ms",
    "spring.kafka.admin.auto-create=true",
    "spring.kafka.listener.auto-startup=true",
    "ecomdemo.search.min-similarity=0.3"
})
class EmbeddingSyncIT extends CatalogIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @TestConfiguration
    static class Model {
        @Bean
        ConceptEmbeddingModel conceptEmbeddingModel() {
            return new ConceptEmbeddingModel();
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a created product becomes searchable, and an edit is re-embedded, with no backfill")
    void createAndEditReachTheIndex() {
        Long id = rest.postForEntity("/api/products",
                new ProductRequest("Travel Pouch", "Padded carry pouch for a notebook computer",
                        new BigDecimal("1499.00"), 4, "ACCESSORIES"),
                ProductResponse.class).getBody().id();

        awaitTrue("the new product was embedded", () -> content(id).contains("Travel Pouch"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND published_at IS NOT NULL",
                Integer.class, id.toString()))
                .as("the relay marked the event published")
                .isEqualTo(1);
        ProductSearchResponse found = rest.getForObject(
                "/api/products/search?q=protect my computer while commuting", ProductSearchResponse.class);
        assertThat(found.results()).extracting(hit -> hit.product().id()).contains(id);

        rest.put("/api/products/" + id, new ProductRequest("Travel Pouch", "Now a pouch for headphones and music",
                new BigDecimal("1499.00"), 4, "ACCESSORIES"));

        awaitTrue("the edit was re-embedded", () -> content(id).contains("headphones"));

        rest.delete("/api/products/" + id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_embedding WHERE id = ?", Integer.class, id))
                .isZero();
    }

    private String content(Long id) {
        return jdbc.query("SELECT content FROM product_embedding WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : "", id);
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("waiting until " + what).isBefore(deadline);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
