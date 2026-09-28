package com.ecomdemo.assistant;

import java.math.BigDecimal;

/** A proposed cart addition, now made; and the cart's total afterwards. */
public record ConfirmedAddition(Long productId, String productName, int quantity, BigDecimal cartTotal) {
}
