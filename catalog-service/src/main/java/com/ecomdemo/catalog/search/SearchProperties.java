package com.ecomdemo.catalog.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The semantic search's settings, bound from {@code ecomdemo.search.*} (Phase 28).
 *
 * @param minSimilarity the cosine similarity below which a product is not a match at all. Nearest
 *     neighbour search ALWAYS returns neighbours - ask for "a sofa" in an electronics shop and the
 *     closest product still comes back - so without a floor, every query "finds" something. The
 *     right number belongs to the embedding model (each spreads its scores differently), which is
 *     why it is configuration and not a constant; the default was measured, see decisions.md
 * @param defaultLimit how many results a search returns when the caller does not say
 * @param maxLimit the most a caller may ask for; every result is a row read and a stock figure
 * @param documentPrefix put in front of every product's text before it is embedded
 * @param queryPrefix put in front of every query before it is embedded. Some models are trained
 *     with a task prefix on each side (nomic-embed-text: {@code search_document: } and
 *     {@code search_query: }) and rank better with them; for others both stay empty
 */
@ConfigurationProperties(prefix = "ecomdemo.search")
public record SearchProperties(
        double minSimilarity, int defaultLimit, int maxLimit, String documentPrefix, String queryPrefix) {

    public SearchProperties {
        documentPrefix = documentPrefix == null ? "" : documentPrefix;
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
        if (defaultLimit <= 0) {
            defaultLimit = 5;
        }
        if (maxLimit <= 0) {
            maxLimit = 20;
        }
    }
}
