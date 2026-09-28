package com.ecomdemo.catalog.search;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The semantic search's settings, bound from {@code ecomdemo.search.*} (Phase 28).
 *
 * <h2>Why three of them depend on the model</h2>
 *
 * The threshold and the prefixes are properties of the EMBEDDING MODEL, not of this service:
 * <ul>
 *   <li>Nearest-neighbour search always returns neighbours - ask for "a sofa" in an electronics shop
 *       and the closest product still comes back - so a minimum similarity decides what counts as a
 *       match at all. But each model spreads its scores differently: nomic-embed-text put every
 *       product between 0.39 and 0.65 for every query measured, while models like OpenAI's are known
 *       to score lower overall. One number cannot suit both.
 *   <li>Some models were trained with a task prefix on each side (nomic-embed-text:
 *       {@code search_query:} and {@code search_document:}) and rank better with them; for others
 *       they are noise.
 * </ul>
 * So each provider has its own defaults under {@code models.<provider>}, and the one named by
 * {@code spring.ai.model.embedding} applies. The top-level values override it when set - blank
 * means "not set", because compose passes an unset variable as an EMPTY one, and an empty value
 * would otherwise beat every default (the Phase 27 lesson).
 *
 * @param provider the active embedding provider ({@code spring.ai.model.embedding})
 * @param minSimilarity overrides the model's default when not null
 * @param documentPrefix overrides the model's default when not blank
 * @param queryPrefix overrides the model's default when not blank
 * @param models each provider's defaults
 * @param defaultLimit how many results a search returns when the caller does not say
 * @param maxLimit the most a caller may ask for; every result is a row read and a stock figure
 */
@ConfigurationProperties(prefix = "ecomdemo.search")
public record SearchProperties(
        String provider,
        Double minSimilarity,
        String documentPrefix,
        String queryPrefix,
        Map<String, ModelDefaults> models,
        int defaultLimit,
        int maxLimit) {

    /** When neither the override nor the model says: low enough to never hide a real match. */
    static final double FALLBACK_MIN_SIMILARITY = 0.3;

    public SearchProperties {
        models = models == null ? Map.of() : Map.copyOf(models);
        if (defaultLimit <= 0) {
            defaultLimit = 5;
        }
        if (maxLimit <= 0) {
            maxLimit = 20;
        }
    }

    public double effectiveMinSimilarity() {
        if (minSimilarity != null) {
            return minSimilarity;
        }
        ModelDefaults defaults = modelDefaults();
        return defaults != null && defaults.minSimilarity() != null ? defaults.minSimilarity() : FALLBACK_MIN_SIMILARITY;
    }

    /** What goes in front of a product's text before it is embedded; empty, or ending in one space. */
    public String effectiveDocumentPrefix() {
        return prefix(documentPrefix, modelDefaults() == null ? null : modelDefaults().documentPrefix());
    }

    /** What goes in front of a query before it is embedded; empty, or ending in one space. */
    public String effectiveQueryPrefix() {
        return prefix(queryPrefix, modelDefaults() == null ? null : modelDefaults().queryPrefix());
    }

    private ModelDefaults modelDefaults() {
        return provider == null ? null : models.get(provider);
    }

    /**
     * The space is added here, not written in the properties file: a trailing space in a
     * {@code .properties} value is invisible in review and deleted by half the editors that open it.
     */
    private static String prefix(String override, String modelDefault) {
        String chosen = override != null && !override.isBlank() ? override : modelDefault;
        return chosen == null || chosen.isBlank() ? "" : chosen.strip() + " ";
    }

    /** One embedding provider's defaults. Any of them may be absent. */
    public record ModelDefaults(Double minSimilarity, String documentPrefix, String queryPrefix) {
    }
}
