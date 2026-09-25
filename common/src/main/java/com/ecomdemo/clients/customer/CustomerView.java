package com.ecomdemo.clients.customer;

import java.time.Instant;

/**
 * An account as another service sees it.
 *
 * <p>No password field, and not because it is omitted from the JSON — because it is omitted from
 * customer-service's response too. A hash never leaves the service that stores it.
 */
public record CustomerView(Long id, String username, String fullName, String role, Instant createdAt) {
}
