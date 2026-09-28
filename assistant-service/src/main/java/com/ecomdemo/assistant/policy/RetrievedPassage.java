package com.ecomdemo.assistant.policy;

/** A passage found for a question, and how close it was in meaning (cosine, 1 = identical). */
public record RetrievedPassage(PolicyPassage passage, double similarity) {
}
