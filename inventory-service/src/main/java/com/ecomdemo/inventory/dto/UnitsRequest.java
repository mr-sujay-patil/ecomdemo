package com.ecomdemo.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * A number of units to take or put back: the body of {@code reserve} and {@code release}.
 *
 * <p><strong>{@code @NotNull} as well as {@code @Positive}, and that pairing is the point.</strong>
 * Bean Validation's {@code @Positive} is satisfied by {@code null} — every constraint except
 * {@code @NotNull} treats an absent value as somebody else's problem. This record replaced a single
 * three-field {@code QuantityRequest} shared by all three endpoints, where no field <em>could</em>
 * be {@code @NotNull} because each endpoint needed a different two of them. The result was that a
 * body missing {@code units} passed validation and became a {@link NullPointerException} unboxing
 * it: a 500 for what is plainly a bad request. One record per request shape is what lets each field
 * say whether it is required.
 *
 * @param units how many. Required, and greater than zero — releasing or reserving nothing is a
 *     caller's bug, not a no-op worth accepting quietly.
 * @param productName only for the error message when there is not enough stock. Optional, and
 *     deliberately so: inventory-service has no catalogue to look a name up in since Phase 20a, and
 *     "product 47" in an error a shopper reads is worse than a name the caller already had.
 */
public record UnitsRequest(
        @NotNull(message = "units is required")
        @Positive(message = "units must be greater than zero")
        Integer units,
        String productName) {
}
