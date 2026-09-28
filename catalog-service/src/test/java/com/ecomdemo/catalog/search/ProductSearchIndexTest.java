package com.ecomdemo.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.shared.ServiceUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("ProductSearchIndex: what is embedded, what is filtered on, and no model at all")
class ProductSearchIndexTest {

    private static final IndexedProduct SLEEVE = new IndexedProduct(10L, "Laptop Sleeve 16\"",
            "Water-resistant padded sleeve for 16-inch laptops", "Accessories", new BigDecimal("1799.00"));

    private static ProductSearchIndex withoutModel(SearchProperties properties) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        return new ProductSearchIndex(beans.getBeanProvider(EmbeddingModel.class), new JdbcTemplate(),
                beans.getBeanProvider(ObservationRegistry.class), properties, new SimpleMeterRegistry());
    }

    private static SearchProperties properties(String documentPrefix) {
        return new SearchProperties(0.5, 5, 20, documentPrefix, "");
    }

    @Test
    @DisplayName("the text is name, description and category in words; the price is not in it")
    void embeddedText() {
        Document document = withoutModel(properties("")).toDocument(SLEEVE);

        assertThat(document.getId()).isEqualTo("10");
        assertThat(document.getText()).isEqualTo(
                "Laptop Sleeve 16\". Water-resistant padded sleeve for 16-inch laptops. Category: accessories");
        assertThat(document.getText()).doesNotContain("1799");
    }

    @Test
    @DisplayName("metadata carries what the filters need: an upper-case category and a numeric price")
    void metadata() {
        Document document = withoutModel(properties("")).toDocument(SLEEVE);

        assertThat(document.getMetadata())
                .containsEntry("productId", 10L)
                .containsEntry("category", "ACCESSORIES")
                .containsEntry("price", 1799.0);
    }

    @Test
    @DisplayName("a model's document prefix goes in front of the text; a product with no category or description still embeds")
    void prefixAndMissingFields() {
        IndexedProduct bare = new IndexedProduct(11L, "Cable", null, null, new BigDecimal("5.00"));

        Document document = withoutModel(properties("search_document: ")).toDocument(bare);

        assertThat(document.getText()).isEqualTo("search_document: Cable");
        assertThat(document.getMetadata()).doesNotContainKey("category");
    }

    @Test
    @DisplayName("filters: none is no expression; each given one is a condition, AND-ed")
    void filters() {
        assertThat(ProductSearchIndex.Filters.NONE.expression()).isEmpty();
        assertThat(new ProductSearchIndex.Filters(" ", null, null).expression()).isEmpty();

        String all = new ProductSearchIndex.Filters("accessories", new BigDecimal("1000"), new BigDecimal("2000"))
                .expression().orElseThrow().toString();
        assertThat(all).contains("ACCESSORIES").contains("GTE").contains("LTE").contains("AND");
    }

    @Test
    @DisplayName("with no model, every operation is a 503 naming the setting, and nothing is attempted")
    void notConfigured() {
        ProductSearchIndex index = withoutModel(properties(""));

        assertThat(index.isConfigured()).isFalse();
        for (Runnable call : List.<Runnable>of(
                () -> index.search("laptop", ProductSearchIndex.Filters.NONE, 5),
                () -> index.upsert(List.of(SLEEVE)),
                () -> index.remove(10L),
                index::requireConfigured)) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(ServiceUnavailableException.class)
                    .hasMessageContaining("AI_EMBEDDING_PROVIDER")
                    .satisfies(e -> assertThat(((ServiceUnavailableException) e).retryAfter())
                            .isEqualTo(Duration.ofMinutes(5)));
        }
    }
}
