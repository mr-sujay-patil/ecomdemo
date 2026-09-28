package com.ecomdemo.catalog.search;

import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.catalog.dto.ProductResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Semantic search: the index says WHICH products and in what order, the catalogue says what they
 * are now (Phase 28).
 *
 * <p>The index could answer on its own - it holds each product's text - but that text is a copy
 * from the moment of embedding, and a price or name edited a second ago would be shown stale.
 * So the ids go back to {@link ProductService}, which reads the current rows and the current
 * stock, and the similarity scores are joined back on.
 */
@Service
public class ProductSearchService {

    private final ProductSearchIndex index;
    private final ProductService products;
    private final SearchProperties properties;

    public ProductSearchService(ProductSearchIndex index, ProductService products, SearchProperties properties) {
        this.index = index;
        this.products = products;
        this.properties = properties;
    }

    public ProductSearchResponse search(String query, ProductSearchIndex.Filters filters, Integer limit) {
        String q = query.strip();
        int size = limit == null ? properties.defaultLimit() : Math.min(limit, properties.maxLimit());
        List<ProductSearchIndex.Match> matches = index.search(q, filters, size);

        Map<Long, Double> similarity = matches.stream().collect(Collectors.toMap(
                ProductSearchIndex.Match::productId, ProductSearchIndex.Match::similarity, (a, b) -> a));
        List<ProductResponse> found =
                products.findAllInOrder(matches.stream().map(ProductSearchIndex.Match::productId).toList());
        return new ProductSearchResponse(q, found.stream()
                .map(product -> new ProductSearchHit(product, round(similarity.get(product.id()))))
                .toList());
    }

    /** Four decimals: more is noise in the JSON, and the ranking is already decided. */
    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }
}
