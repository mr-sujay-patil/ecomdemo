package com.ecomdemo.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A deterministic stand-in for an embedding model: it knows a handful of CONCEPTS, each a list of
 * word stems, and a text's vector says which concepts it mentions.
 *
 * <p>That is a toy, but it has the one property the search code depends on: texts that share a
 * concept and no words still land close together. "protect my computer while commuting" and
 * "Water-resistant padded sleeve for 16-inch laptops" have no word in common - keyword search misses
 * - yet both hit TRANSPORT and LAPTOP, so their vectors point the same way. A real model does this
 * for every concept in the language; this one for the six the tests need, and identically on every
 * run, which a real one on a CI machine would not be.
 *
 * <p>Every vector also has a small component in a dimension picked by the text's hash. Two jobs:
 * a zero vector has no direction (its cosine with anything is undefined, pgvector returns NaN), and
 * two texts that mention NO concept must not look identical - with a shared constant they would
 * point the same way and score 1.0, and "a garden hose" would match the monitor perfectly.
 */
public class ConceptEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 768;

    private static final List<List<String>> CONCEPTS = List.of(
            List.of("sleeve", "bag", "carry", "protect", "padded", "commut", "travel", "train"),  // TRANSPORT
            List.of("laptop", "computer", "notebook"),                                            // LAPTOP
            List.of("stand", "desk", "ergonomic", "posture"),                                     // DESK
            List.of("headphone", "noise", "quiet", "music", "listen", "flight"),                   // AUDIO
            List.of("ssd", "storage", "files", "backup", "drive"),                                // STORAGE
            List.of("keyboard", "typing", "type", "keys"));                                       // TYPING

    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    /** Every call fails with this until {@link #recover()}: the model being down. */
    public void failWith(RuntimeException e) {
        failure.set(e);
    }

    public void recover() {
        failure.set(null);
    }

    /** How many times the model was called (one call may embed many texts). */
    public int calls() {
        return calls.get();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        RuntimeException e = failure.get();
        if (e != null) {
            throw e;
        }
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vector(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput();
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    static float[] vector(String text) {
        float[] vector = new float[DIMENSIONS];
        String lower = text.toLowerCase(Locale.ROOT);
        String[] words = lower.split("[^a-z0-9]+");
        for (int concept = 0; concept < CONCEPTS.size(); concept++) {
            for (String word : words) {
                if (CONCEPTS.get(concept).stream().anyMatch(word::startsWith)) {
                    vector[concept] = 1f;
                    break;
                }
            }
        }
        int own = CONCEPTS.size() + Math.floorMod(lower.hashCode(), DIMENSIONS - CONCEPTS.size());
        vector[own] = 0.1f;
        return vector;
    }
}
