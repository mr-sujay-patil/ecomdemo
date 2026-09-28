package com.ecomdemo.assistant;

import com.ecomdemo.assistant.actions.PendingCartAddition;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The assistant's answer, and what it was based on.
 *
 * @param conversationId send it back to continue this conversation
 * @param answer the text to show the customer
 * @param sources the policies and products the answer could draw on, and the orders it looked at
 * @param toolsUsed the tools the model called, in order
 * @param pendingAction a cart addition waiting for the customer's confirmation, or null
 */
public record AssistantReply(
        String conversationId,
        String answer,
        List<Source> sources,
        List<String> toolsUsed,
        @Schema(nullable = true) PendingCartAddition pendingAction) {

    /**
     * Something the answer was grounded in.
     *
     * @param type {@code policy}, {@code product} or {@code order}
     * @param id the policy passage's id, the product id or the order id
     * @param title what a person would call it
     */
    public record Source(String type, String id, String title) {
    }
}
