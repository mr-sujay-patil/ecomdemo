package com.ecomdemo.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OrderClaimGuard: an order status needs an order lookup behind it")
class OrderClaimGuardTest {

    @Test
    @DisplayName("the invented answer from the evaluation run is caught")
    void inventedOrders() {
        String answer = "Order 30 is not an order on eval-shopper-a's account. Here are the orders for "
                + "eval-shopper-a:\n\nOrder 1: PENDING\nOrder 2: CONFIRMED";

        assertThat(OrderClaimGuard.unsupported(answer, false)).isTrue();
    }

    @Test
    @DisplayName("the same claim is fine when a lookup found the order")
    void backedByALookup() {
        assertThat(OrderClaimGuard.unsupported("Your order 23 is confirmed.", true)).isFalse();
    }

    @Test
    @DisplayName("answers without a status claim pass: a refusal, a proposal to confirm, a product answer")
    void noClaim() {
        assertThat(OrderClaimGuard.unsupported("Order 30 is not an order on your account.", false)).isFalse();
        assertThat(OrderClaimGuard.unsupported("Please confirm the addition of 1 x Desk Mat to your cart.", false)).isFalse();
        assertThat(OrderClaimGuard.unsupported("The Noise-Cancelling Headphones cost 14999 rupees.", false)).isFalse();
        assertThat(OrderClaimGuard.unsupported(
                "An order is dispatched once it is CONFIRMED; a PENDING order is still waiting for payment.", false))
                .as("explaining the statuses, as the shipping policy does, names no order").isFalse();
    }
}
