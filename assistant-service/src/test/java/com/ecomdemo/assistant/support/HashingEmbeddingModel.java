package com.ecomdemo.assistant.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A deterministic stand-in for an embedding model: each word is hashed to one of 256 dimensions,
 * so two texts are "close" when they share words. Nothing like the meaning a real model captures -
 * which is fine, because what the tests check is the pipeline around it: that passages are ranked,
 * thresholded, embedded once, and put in front of the model.
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 256;

    private final AtomicInteger calls = new AtomicInteger();
    private volatile RuntimeException failure;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        RuntimeException pending = failure;
        if (pending != null) {
            throw pending;
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
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    public int calls() {
        return calls.get();
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void reset() {
        calls.set(0);
        failure = null;
    }

    static float[] vector(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() > 2) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1;
            }
        }
        return vector;
    }
}
