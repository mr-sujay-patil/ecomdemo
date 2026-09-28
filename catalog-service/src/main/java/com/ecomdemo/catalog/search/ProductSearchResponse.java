package com.ecomdemo.catalog.search;

import java.util.List;

/** The query as understood, and the matches, best first. An empty list means nothing was close enough. */
public record ProductSearchResponse(String query, List<ProductSearchHit> results) {
}
