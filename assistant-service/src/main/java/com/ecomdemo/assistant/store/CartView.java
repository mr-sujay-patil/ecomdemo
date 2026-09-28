package com.ecomdemo.assistant.store;

import java.math.BigDecimal;
import java.util.List;

/** The cart as the application returns it after a change. */
public record CartView(Long id, List<Line> items, BigDecimal totalAmount) {

    public record Line(Long productId, String productName, int quantity) {
    }
}
