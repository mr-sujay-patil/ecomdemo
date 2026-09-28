package com.ecomdemo.assistant.actions;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * A cart addition waiting for the customer's confirmation. The name and price are what the
 * customer is shown when asked to confirm; the cart itself takes its price from the catalogue.
 */
@Schema(description = "A cart addition the assistant proposed. Nothing is in the cart until it is confirmed.")
public record PendingCartAddition(
        @Schema(description = "Confirm with POST /api/assistant/actions/{id}/confirm.") String id,
        Long productId,
        String productName,
        int quantity,
        BigDecimal unitPrice) {
}
