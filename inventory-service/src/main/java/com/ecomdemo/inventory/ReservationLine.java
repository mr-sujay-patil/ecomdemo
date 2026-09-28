package com.ecomdemo.inventory;

/**
 * One line of an order, as the saga asks inventory to reserve it.
 *
 * @param productId which product
 * @param productName for the rejection message; this service cannot look a name up
 * @param quantity how many
 */
public record ReservationLine(Long productId, String productName, int quantity) {
}
