package com.ecomdemo.assistant.policy;

/**
 * One section of a policy document.
 *
 * @param id stable reference, {@code <document>#<section>}, returned to the client as a source
 * @param heading "Document title - Section", what a person would call it
 * @param text the heading and the section's text: what is embedded and what the model is shown
 */
public record PolicyPassage(String id, String heading, String text) {
}
