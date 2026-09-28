package com.ecomdemo.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One message to the assistant.
 *
 * <p>The size limit is a guardrail too: a message is sent to the model on this turn and replayed on
 * the next ones, so a long one costs tokens many times - and is the easiest place to hide a long
 * injected instruction.
 */
public record ChatRequest(
        @Schema(description = "Continue a conversation: the id a previous answer returned. Omit to start one.",
                example = "2f1c7a36-5a5e-4c55-9d0e-0f1a8f0f3b9e", nullable = true)
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "must be an id returned by a previous answer")
        String conversationId,

        @Schema(description = "The customer's message.", example = "Which headphones are good for flights?")
        @NotBlank(message = "is required")
        @Size(max = ChatRequest.MAX_LENGTH, message = "must be at most " + ChatRequest.MAX_LENGTH + " characters")
        String message) {

    public static final int MAX_LENGTH = 1000;
}
