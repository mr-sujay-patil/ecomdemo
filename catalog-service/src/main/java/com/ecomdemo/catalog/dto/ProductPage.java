package com.ecomdemo.catalog.dto;

import java.util.List;

/**
 * One page of the catalogue plus how many products there are in all (KI-007).
 *
 * <p>What the listing caches and what the controller turns into a bare JSON array and the
 * {@code X-Total-Count} / {@code Link} headers. It is never serialised to a client as it is, so the
 * response body stays the array the web team already reads.
 */
public record ProductPage(List<ProductResponse> items, long total) {
}
