package com.ecomdemo.assistant;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The assistant's settings, bound from {@code ecomdemo.assistant.*} (Phase 29).
 *
 * <p>Most of these are LIMITS, and each one is a guardrail with a number on it: how much of a
 * conversation is replayed to the model, how long an unconfirmed cart addition stays valid, how many
 * products one search shows it. A language model has no sense of "enough"; every one of these is a
 * place where the code has to supply it. (How many tools one answer may call is Spring AI's own
 * {@code spring.ai.tools.limits.*}; how long a message may be is on {@code ChatRequest}.)
 *
 * @param catalogBaseUrl where catalog-service is (product search and lookup)
 * @param appBaseUrl where the application is (orders and the cart)
 * @param connectTimeout TCP connect limit for both
 * @param readTimeout response limit for both; a search includes one embedding call
 * @param memoryMessages how many messages of the conversation are replayed to the model each turn
 * @param memoryTtl how long an idle conversation is kept
 * @param actionTtl how long a proposed cart addition can still be confirmed
 * @param productResults how many products one search returns to the model
 * @param retrieval how the policy passages are found
 */
@ConfigurationProperties(prefix = "ecomdemo.assistant")
public record AssistantProperties(
        String catalogBaseUrl,
        String appBaseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int memoryMessages,
        Duration memoryTtl,
        Duration actionTtl,
        int productResults,
        Retrieval retrieval) {

    public AssistantProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        memoryMessages = memoryMessages <= 0 ? 20 : memoryMessages;
        memoryTtl = memoryTtl == null ? Duration.ofHours(24) : memoryTtl;
        actionTtl = actionTtl == null ? Duration.ofMinutes(10) : actionTtl;
        productResults = productResults <= 0 ? 5 : productResults;
        retrieval = retrieval == null ? new Retrieval(null, null, null, null, null, 0) : retrieval;
    }

    /**
     * Policy retrieval. The same per-model reasoning as catalog-service's search (Phase 28): the
     * threshold and the task prefixes belong to the embedding model, so each provider has its own
     * defaults and the active one applies; a top-level value overrides it, and blank means unset.
     *
     * @param provider the active embedding provider ({@code spring.ai.model.embedding})
     * @param minSimilarity overrides the model's default when not null
     * @param documentPrefix overrides the model's default when not blank
     * @param queryPrefix overrides the model's default when not blank
     * @param models each provider's defaults
     * @param passages the most policy passages put in front of the model per question
     */
    public record Retrieval(
            String provider,
            Double minSimilarity,
            String documentPrefix,
            String queryPrefix,
            Map<String, ModelDefaults> models,
            int passages) {

        /** When neither the override nor the model says. */
        static final double FALLBACK_MIN_SIMILARITY = 0.3;

        public Retrieval {
            models = models == null ? Map.of() : Map.copyOf(models);
            passages = passages <= 0 ? 3 : passages;
        }

        public double effectiveMinSimilarity() {
            if (minSimilarity != null) {
                return minSimilarity;
            }
            ModelDefaults defaults = modelDefaults();
            return defaults != null && defaults.minSimilarity() != null
                    ? defaults.minSimilarity() : FALLBACK_MIN_SIMILARITY;
        }

        /** Empty, or ending in one space. */
        public String effectiveDocumentPrefix() {
            return prefix(documentPrefix, modelDefaults() == null ? null : modelDefaults().documentPrefix());
        }

        /** Empty, or ending in one space. */
        public String effectiveQueryPrefix() {
            return prefix(queryPrefix, modelDefaults() == null ? null : modelDefaults().queryPrefix());
        }

        private ModelDefaults modelDefaults() {
            return provider == null ? null : models.get(provider);
        }

        private static String prefix(String override, String modelDefault) {
            String chosen = override != null && !override.isBlank() ? override : modelDefault;
            return chosen == null || chosen.isBlank() ? "" : chosen.strip() + " ";
        }
    }

    /** One embedding provider's defaults. Any of them may be absent. */
    public record ModelDefaults(Double minSimilarity, String documentPrefix, String queryPrefix) {
    }
}
