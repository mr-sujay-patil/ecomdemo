package com.ecomdemo.inventory;

/**
 * What became of an order's reservation. Both are ordinary outcomes of the saga, not errors.
 *
 * @param reserved whether every line was reserved
 * @param rejectionReason why not, in the shopper's terms; {@code null} when reserved
 */
public record ReservationResult(boolean reserved, String rejectionReason) {

    static ReservationResult reservedAll() {
        return new ReservationResult(true, null);
    }

    static ReservationResult rejected(String reason) {
        return new ReservationResult(false, reason);
    }
}
