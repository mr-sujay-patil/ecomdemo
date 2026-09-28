package com.ecomdemo.assistant.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.assistant.AssistantProperties;
import com.ecomdemo.assistant.AssistantProperties.ModelDefaults;
import com.ecomdemo.assistant.AssistantProperties.Retrieval;
import com.ecomdemo.assistant.support.HashingEmbeddingModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

@DisplayName("PolicyLibrary: chunking, and retrieval over the passages")
class PolicyLibraryTest {

    private static AssistantProperties properties(Retrieval retrieval) {
        return new AssistantProperties("http://catalog", "http://app", null, null, 0, null, null, 0, retrieval);
    }

    private static PolicyLibrary library(List<PolicyPassage> passages, EmbeddingModel model, Retrieval retrieval) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("embeddingModel", model);
        return new PolicyLibrary(passages, beans.getBeanProvider(EmbeddingModel.class), properties(retrieval));
    }

    @Test
    @DisplayName("one passage per ## section, each carrying its document's title; the id is document#section")
    void chunksBySection() {
        List<PolicyPassage> passages = PolicyLibrary.chunk("returns.md", """
                # Returns and refunds

                Some introduction.

                ## Return window
                30 days.

                ## Refunds & money
                5 to 7 business days.
                """);

        assertThat(passages).extracting(PolicyPassage::id)
                .containsExactly("returns", "returns#return-window", "returns#refunds-money");
        assertThat(passages.get(1).heading()).isEqualTo("Returns and refunds - Return window");
        assertThat(passages.get(1).text()).isEqualTo("Returns and refunds - Return window\n30 days.");
    }

    @Test
    @DisplayName("the shipped policies load: every passage has text, and the ids are unique")
    void shippedPoliciesLoad() {
        List<PolicyPassage> passages = PolicyLibrary.load("classpath:policies/*.md");

        assertThat(passages).hasSizeGreaterThanOrEqualTo(15);
        assertThat(passages).extracting(PolicyPassage::id).doesNotHaveDuplicates()
                .contains("shipping#shipping-charges", "returns#return-window", "warranty#displays");
        assertThat(passages).allSatisfy(passage -> assertThat(passage.text().lines().count()).isGreaterThan(1));
    }

    @Test
    @DisplayName("retrieval ranks by similarity, drops what is below the threshold, and keeps at most N")
    void ranksThresholdsAndLimits() {
        List<PolicyPassage> passages = List.of(
                new PolicyPassage("a", "A", "shipping charges rupees standard"),
                new PolicyPassage("b", "B", "shipping charges express"),
                new PolicyPassage("c", "C", "warranty monitors pixels"),
                new PolicyPassage("d", "D", "shipping"));
        PolicyLibrary library = library(passages, new HashingEmbeddingModel(),
                new Retrieval(null, 0.2, null, null, null, 2));

        List<RetrievedPassage> found = library.relevant("standard shipping charges");

        assertThat(found).extracting(r -> r.passage().id()).containsExactly("a", "b");
        assertThat(found.get(0).similarity()).isGreaterThan(found.get(1).similarity());
    }

    @Test
    @DisplayName("the passages are embedded in ONE call on first use; a failure is retried on the next question")
    void embedsOnceAndRetriesAfterAFailure() {
        HashingEmbeddingModel model = new HashingEmbeddingModel();
        PolicyLibrary library = library(PolicyLibrary.load("classpath:policies/*.md"), model,
                new Retrieval(null, 0.1, null, null, null, 3));

        model.failWith(new IllegalStateException("model down"));
        assertThatThrownBy(() -> library.relevant("returns")).hasMessage("model down");

        model.reset();
        library.relevant("returns");
        library.relevant("warranty");
        assertThat(model.calls()).as("one batch for the passages, then one per question").isEqualTo(3);
    }

    @Test
    @DisplayName("the model's task prefixes go on the passages and on the question")
    void prefixes() {
        RecordingModel model = new RecordingModel();
        PolicyLibrary library = library(List.of(new PolicyPassage("a", "A", "shipping")), model,
                new Retrieval("ollama", null, null, null,
                        Map.of("ollama", new ModelDefaults(0.0, "search_document:", "search_query:")), 3));

        library.relevant("ship abroad?");

        assertThat(model.texts).containsExactly("search_document: shipping", "search_query: ship abroad?");
    }

    @Test
    @DisplayName("cosine: same direction 1, orthogonal 0, and a zero vector is 0 rather than NaN")
    void cosine() {
        assertThat(PolicyLibrary.cosine(new float[] {1, 2}, new float[] {2, 4})).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(PolicyLibrary.cosine(new float[] {1, 0}, new float[] {0, 3})).isZero();
        assertThat(PolicyLibrary.cosine(new float[] {0, 0}, new float[] {1, 1})).isZero();
    }

    /** Records every text it is asked to embed. */
    private static final class RecordingModel extends HashingEmbeddingModel {

        private final List<String> texts = new java.util.ArrayList<>();

        @Override
        public org.springframework.ai.embedding.EmbeddingResponse call(
                org.springframework.ai.embedding.EmbeddingRequest request) {
            texts.addAll(request.getInstructions());
            return super.call(request);
        }
    }
}
