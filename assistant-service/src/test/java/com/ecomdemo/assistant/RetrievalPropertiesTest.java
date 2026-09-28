package com.ecomdemo.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.assistant.AssistantProperties.ModelDefaults;
import com.ecomdemo.assistant.AssistantProperties.Retrieval;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("policy retrieval settings: the active model's defaults, overridable, blank = unset")
class RetrievalPropertiesTest {

    private static final Map<String, ModelDefaults> MODELS = Map.of(
            "ollama", new ModelDefaults(0.5, "search_document:", "search_query:"),
            "openai", new ModelDefaults(0.3, null, null));

    @Test
    @DisplayName("the active provider's defaults apply, with the prefix's space added in code")
    void modelDefaults() {
        Retrieval ollama = new Retrieval("ollama", null, "", " ", MODELS, 0);

        assertThat(ollama.effectiveMinSimilarity()).isEqualTo(0.5);
        assertThat(ollama.effectiveDocumentPrefix()).isEqualTo("search_document: ");
        assertThat(ollama.effectiveQueryPrefix()).isEqualTo("search_query: ");
        assertThat(ollama.passages()).isEqualTo(3);

        Retrieval openai = new Retrieval("openai", null, null, null, MODELS, 2);
        assertThat(openai.effectiveMinSimilarity()).isEqualTo(0.3);
        assertThat(openai.effectiveQueryPrefix()).isEmpty();
    }

    @Test
    @DisplayName("an override wins; an unknown provider falls back to 0.3 and no prefixes")
    void overridesAndFallback() {
        assertThat(new Retrieval("ollama", 0.42, "doc:", null, MODELS, 3).effectiveMinSimilarity()).isEqualTo(0.42);
        assertThat(new Retrieval("ollama", null, "doc:", null, MODELS, 3).effectiveDocumentPrefix()).isEqualTo("doc: ");

        Retrieval none = new Retrieval("none", null, null, null, null, 3);
        assertThat(none.effectiveMinSimilarity()).isEqualTo(0.3);
        assertThat(none.effectiveDocumentPrefix()).isEmpty();
    }
}
