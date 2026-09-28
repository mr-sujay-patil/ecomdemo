package com.ecomdemo.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SearchProperties: the active model's defaults, unless overridden")
class SearchPropertiesTest {

    private static final Map<String, SearchProperties.ModelDefaults> MODELS = Map.of(
            "ollama", new SearchProperties.ModelDefaults(0.5, "search_document:", "search_query:"),
            "openai", new SearchProperties.ModelDefaults(0.3, null, null));

    @Test
    @DisplayName("the provider picks its own threshold and prefixes, with the space added")
    void modelDefaults() {
        SearchProperties ollama = new SearchProperties("ollama", null, null, null, MODELS, 5, 20);
        SearchProperties openai = new SearchProperties("openai", null, null, null, MODELS, 5, 20);

        assertThat(ollama.effectiveMinSimilarity()).isEqualTo(0.5);
        assertThat(ollama.effectiveQueryPrefix()).isEqualTo("search_query: ");
        assertThat(ollama.effectiveDocumentPrefix()).isEqualTo("search_document: ");
        assertThat(openai.effectiveMinSimilarity()).isEqualTo(0.3);
        assertThat(openai.effectiveQueryPrefix()).isEmpty();
    }

    @Test
    @DisplayName("an override wins; a BLANK one does not, because compose sends unset variables as empty")
    void overrides() {
        SearchProperties set = new SearchProperties("ollama", 0.7, "doc:", "q:", MODELS, 5, 20);
        SearchProperties blank = new SearchProperties("ollama", null, " ", "", MODELS, 5, 20);

        assertThat(set.effectiveMinSimilarity()).isEqualTo(0.7);
        assertThat(set.effectiveQueryPrefix()).isEqualTo("q: ");
        assertThat(blank.effectiveQueryPrefix()).isEqualTo("search_query: ");
        assertThat(blank.effectiveDocumentPrefix()).isEqualTo("search_document: ");
    }

    @Test
    @DisplayName("an unknown provider (or none) falls back to a low threshold and no prefixes")
    void fallback() {
        SearchProperties none = new SearchProperties("none", null, null, null, MODELS, 0, 0);

        assertThat(none.effectiveMinSimilarity()).isEqualTo(SearchProperties.FALLBACK_MIN_SIMILARITY);
        assertThat(none.effectiveQueryPrefix()).isEmpty();
        assertThat(none.defaultLimit()).isEqualTo(5);
        assertThat(none.maxLimit()).isEqualTo(20);
    }
}
