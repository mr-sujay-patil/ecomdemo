package com.ecomdemo.clients.customer;

/** Wire shape for customer-service's API. Its own DTOs stay its own; the JSON is the contract. */
public record TokenView(String accessToken, String tokenType, long expiresInSeconds) {
}
