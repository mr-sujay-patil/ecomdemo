package com.ecomdemo.assistant.policy;

import com.ecomdemo.assistant.AssistantProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The store's policies, and the retrieval half of RAG (Phase 29).
 *
 * <h2>The pipeline, all of it in this class</h2>
 *
 * <ol>
 *   <li><b>Load</b> the Markdown files under {@code classpath:policies/} - written by people, shipped
 *       in the jar, versioned with the code that relies on them.
 *   <li><b>Chunk</b> them: one passage per {@code ##} section. A passage is what the model is shown,
 *       so it must make sense alone - hence the document's title in front of every one - and must be
 *       small enough that three of them do not crowd out the question. A heading is where a person
 *       already decided "this is one topic", which beats cutting every N characters mid-sentence.
 *   <li><b>Embed</b> every passage once, in ONE call, the first time a question needs them.
 *   <li><b>Retrieve</b>: embed the question, rank the passages by cosine similarity, keep the best
 *       few above a threshold.
 * </ol>
 * The augmentation and generation halves are in {@code AssistantService}: the passages go into the
 * system prompt, and the model is told to answer from them.
 *
 * <h2>Why in memory, not in pgvector like the products</h2>
 *
 * The products are a large, changing table owned by another service; their vectors had to live next
 * to them. These are about twenty passages that change only when the jar does. Every instance
 * embeds them on first use (well under a second) and holds them in a few hundred kilobytes; a
 * database to hold them would be more to run for nothing it could do better. The trade-off: each
 * instance pays that first embedding call itself, and a question arriving while the embedding model
 * is down gets a 503 rather than an answer without policies.
 */
@Component
public class PolicyLibrary {

    private static final Logger log = LoggerFactory.getLogger(PolicyLibrary.class);

    private final List<PolicyPassage> passages;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final AssistantProperties.Retrieval settings;

    /** One vector per passage, in the same order; null until the first question. */
    private volatile List<float[]> vectors;

    @Autowired
    public PolicyLibrary(ObjectProvider<EmbeddingModel> embeddingModel, AssistantProperties properties) {
        this(load("classpath:policies/*.md"), embeddingModel, properties);
    }

    PolicyLibrary(List<PolicyPassage> passages, ObjectProvider<EmbeddingModel> embeddingModel,
            AssistantProperties properties) {
        this.passages = List.copyOf(passages);
        this.embeddingModel = embeddingModel;
        this.settings = properties.retrieval();
        log.info("Loaded {} policy passages", this.passages.size());
    }

    public List<PolicyPassage> passages() {
        return passages;
    }

    /**
     * The passages most relevant to {@code question}, best first, at most
     * {@code retrieval.passages} of them and none below the threshold. Throws whatever the
     * embedding model throws; the caller turns that into a 503.
     */
    public List<RetrievedPassage> relevant(String question) {
        EmbeddingModel model = embeddingModel.getObject();
        List<float[]> indexed = vectors(model);
        float[] query = model.embed(settings.effectiveQueryPrefix() + question);
        double threshold = settings.effectiveMinSimilarity();
        List<RetrievedPassage> scored = new ArrayList<>();
        for (int i = 0; i < passages.size(); i++) {
            double similarity = cosine(query, indexed.get(i));
            if (similarity >= threshold) {
                scored.add(new RetrievedPassage(passages.get(i), similarity));
            }
        }
        scored.sort(Comparator.comparingDouble(RetrievedPassage::similarity).reversed());
        return scored.stream().limit(settings.passages()).toList();
    }

    /**
     * Embeds every passage on first use. Synchronized so that ten first questions arriving together
     * make one call, not ten; a failure leaves {@code vectors} null and the next question tries again.
     */
    private List<float[]> vectors(EmbeddingModel model) {
        List<float[]> ready = vectors;
        if (ready != null) {
            return ready;
        }
        synchronized (this) {
            if (vectors == null) {
                List<String> texts = passages.stream()
                        .map(passage -> settings.effectiveDocumentPrefix() + passage.text())
                        .toList();
                vectors = List.copyOf(model.embed(texts));
                log.info("Embedded {} policy passages", vectors.size());
            }
            return vectors;
        }
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalStateException(
                    "Vectors of different sizes (%d and %d): was the embedding model changed?".formatted(a.length, b.length));
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    static List<PolicyPassage> load(String pattern) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename));
            List<PolicyPassage> all = new ArrayList<>();
            for (Resource resource : resources) {
                all.addAll(chunk(resource.getFilename(), resource.getContentAsString(StandardCharsets.UTF_8)));
            }
            return all;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the policy documents", e);
        }
    }

    /**
     * One passage per {@code ##} section, each prefixed with the document's {@code #} title. Text
     * before the first section (an introduction) becomes a passage of its own when there is any.
     */
    static List<PolicyPassage> chunk(String fileName, String markdown) {
        String document = fileName.replaceFirst("\\.md$", "");
        String title = document;
        List<PolicyPassage> passages = new ArrayList<>();
        String section = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            if (line.startsWith("# ")) {
                title = line.substring(2).strip();
            } else if (line.startsWith("## ")) {
                add(passages, document, title, section, body);
                section = line.substring(3).strip();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        add(passages, document, title, section, body);
        return passages;
    }

    private static void add(List<PolicyPassage> passages, String document, String title, String section,
            StringBuilder body) {
        String text = body.toString().strip();
        if (text.isEmpty()) {
            return;
        }
        String heading = section == null ? title : title + " - " + section;
        String id = section == null ? document : document + "#" + slug(section);
        passages.add(new PolicyPassage(id, heading, heading + "\n" + text));
    }

    private static String slug(String heading) {
        return heading.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
