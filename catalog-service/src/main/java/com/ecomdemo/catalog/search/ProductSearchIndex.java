package com.ecomdemo.catalog.search;

import com.ecomdemo.shared.ServiceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIdType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one place that reads and writes product embeddings (Phase 28).
 *
 * <h2>What an embedding is, and why cosine</h2>
 *
 * An embedding model turns a text into a list of 768 numbers - a point in a 768-dimensional space -
 * trained so that texts which MEAN similar things land close together, whatever words they use.
 * "Keeps my drinks cold" and "insulated bottle" share no word and still end up neighbours. Search
 * is then geometry: embed the query, and return the products whose points are nearest. "Nearest"
 * here is the angle between the two vectors (cosine similarity, 1 = same direction), because the
 * direction carries the meaning and the length mostly carries how long the text was.
 *
 * <h2>Why the store is built here, by hand</h2>
 *
 * Spring AI's auto-configured {@code PgVectorStore} needs an {@link EmbeddingModel} bean, and with
 * {@code AI_EMBEDDING_PROVIDER=none} there is none: the service would not start. So its
 * auto-configuration is off ({@code spring.ai.vectorstore.type=none}) and the store is built below
 * only when a model exists - Phase 27's {@code ChatClient} decision again. Without one, every
 * method here says so with a 503, and the rest of the catalogue is untouched.
 *
 * <h2>Where the schema comes from</h2>
 *
 * V4 creates the table; {@code initializeSchema(false)} keeps the library from doing it too, and
 * {@code vectorTableValidationsEnabled(true)} makes it check at startup that the table V4 made is
 * the one it expects. {@link PgIdType#BIGSERIAL} because the document id IS the product id (a
 * {@code BIGINT}), which makes every write an idempotent upsert of that product's one row.
 */
@Component
public class ProductSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchIndex.class);

    /** The table V4 creates. */
    static final String TABLE = "product_embedding";

    /** vector(768) in V4. Both supported models are configured to produce exactly this many. */
    static final int DIMENSIONS = 768;

    static final String INDEXING = "ecomdemo.search.indexing";
    static final String SEARCHES = "ecomdemo.search.queries";

    private static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private final VectorStore store;
    private final SearchProperties properties;
    private final MeterRegistry meterRegistry;

    public ProductSearchIndex(
            ObjectProvider<EmbeddingModel> embeddingModel,
            JdbcTemplate jdbcTemplate,
            ObjectProvider<ObservationRegistry> observations,
            SearchProperties properties,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        EmbeddingModel model = embeddingModel.getIfAvailable();
        this.store = model == null ? null : buildStore(jdbcTemplate, model,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    private static PgVectorStore buildStore(
            JdbcTemplate jdbcTemplate, EmbeddingModel model, ObservationRegistry observations) {
        PgVectorStore store = PgVectorStore.builder(jdbcTemplate, model)
                .schemaName("public")
                .vectorTableName(TABLE)
                .idType(PgIdType.BIGSERIAL)
                // Set, never left for the library to discover: unset, it asks the model for the
                // number, which is a network call during startup to a service that may be down.
                .dimensions(DIMENSIONS)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .observationRegistry(observations)
                .build();
        // Not a bean, so Spring will not call this for us; it runs the table validation.
        store.afterPropertiesSet();
        return store;
    }

    public boolean isConfigured() {
        return store != null;
    }

    /**
     * Embeds and saves these products, replacing any embedding they already had. The model is
     * called once for the whole list (the store batches), which is why the backfill hands over a
     * chunk at a time rather than one product.
     */
    public void upsert(List<? extends IndexedProduct> products) {
        requireConfigured();
        if (products.isEmpty()) {
            return;
        }
        store.add(products.stream().map(this::toDocument).toList());
    }

    /** Removes a product's embedding. Deleting the product already did (V4's cascade); this is for a stale row. */
    public void remove(Long productId) {
        requireConfigured();
        store.delete(List.of(productId.toString()));
    }

    /** Records how an indexing attempt ended, for {@code ecomdemo_search_indexing_seconds}. */
    void record(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(INDEXING)
                .description("Product embedding updates, by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    /**
     * The products nearest in meaning to {@code query} that pass the filters, best first, with
     * their similarity. Every failure of the model is a 503: search being down must not look like
     * "no products match".
     */
    public List<Match> search(String query, Filters filters, int limit) {
        if (store == null) {
            throw notConfigured();
        }
        SearchRequest.Builder request = SearchRequest.builder()
                .query(properties.queryPrefix() + query)
                .topK(limit)
                .similarityThreshold(properties.minSimilarity());
        filters.expression().ifPresent(request::filterExpression);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<Document> documents;
        try {
            documents = store.similaritySearch(request.build());
        } catch (RuntimeException e) {
            log.warn("Semantic search failed: {}", e.toString());
            stopSearch(sample, "failed");
            throw new ServiceUnavailableException(
                    "Search is unavailable: the embedding model did not answer.", RETRY_AFTER, e);
        }
        stopSearch(sample, documents.isEmpty() ? "empty" : "found");
        return documents.stream()
                .map(document -> new Match(Long.valueOf(document.getId()),
                        document.getScore() == null ? 0 : document.getScore()))
                .toList();
    }

    private void stopSearch(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(SEARCHES)
                .description("Semantic product searches, by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    /**
     * What is embedded is what a shopper would describe: the name, the description and the
     * category, in words. The price is NOT in the text: "under 3000" is a filter, and a number
     * in an embedding is a word like any other, close to other numbers in spelling, not in value.
     */
    Document toDocument(IndexedProduct product) {
        StringBuilder text = new StringBuilder(properties.documentPrefix()).append(product.name());
        if (product.description() != null && !product.description().isBlank()) {
            text.append(". ").append(product.description());
        }
        if (product.category() != null && !product.category().isBlank()) {
            text.append(". Category: ").append(product.category().toLowerCase(Locale.ROOT));
        }
        // Metadata is what the filters run on (as JSON paths in SQL). Category upper-cased so a
        // filter matches whatever case it was typed in; price as a JSON number so >= and <= compare
        // numbers, not strings.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("productId", product.id());
        metadata.put("name", product.name());
        if (product.category() != null) {
            metadata.put("category", product.category().toUpperCase(Locale.ROOT));
        }
        metadata.put("price", product.price().doubleValue());
        return Document.builder().id(product.id().toString()).text(text.toString()).metadata(metadata).build();
    }

    /** A 503 with the setup steps when no embedding model is configured. */
    public void requireConfigured() {
        if (store == null) {
            throw notConfigured();
        }
    }

    private static ServiceUnavailableException notConfigured() {
        return new ServiceUnavailableException(
                "Semantic search is not configured. Set AI_EMBEDDING_PROVIDER to openai (with "
                        + "OPENAI_API_KEY) or ollama, restart catalog-service, then run the backfill: "
                        + "POST /api/products/embeddings/backfill.",
                Duration.ofMinutes(5), null);
    }

    /** One hit: the product and how close it is in meaning (cosine similarity, 1 = identical). */
    public record Match(Long productId, double similarity) {
    }

    /**
     * The metadata filters. Applied INSIDE the vector query, not afterwards: filtering the top 5
     * after the fact could leave none that match, while the database, asked for "the 5 nearest
     * ACCESSORIES under 3000", returns five if five exist.
     */
    public record Filters(String category, BigDecimal minPrice, BigDecimal maxPrice) {

        public static final Filters NONE = new Filters(null, null, null);

        Optional<org.springframework.ai.vectorstore.filter.Filter.Expression> expression() {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            List<FilterExpressionBuilder.Op> conditions = new ArrayList<>();
            if (category != null && !category.isBlank()) {
                conditions.add(b.eq("category", category.strip().toUpperCase(Locale.ROOT)));
            }
            if (minPrice != null) {
                conditions.add(b.gte("price", minPrice.doubleValue()));
            }
            if (maxPrice != null) {
                conditions.add(b.lte("price", maxPrice.doubleValue()));
            }
            return conditions.stream().reduce(b::and).map(FilterExpressionBuilder.Op::build);
        }
    }
}
