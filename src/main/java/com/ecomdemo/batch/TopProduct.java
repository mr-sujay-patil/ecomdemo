package com.ecomdemo.batch;

import java.math.BigDecimal;

/**
 * One line of the sales report's best-seller table.
 *
 * <p>Read straight out of an aggregate query rather than assembled from entities: the report
 * wants sums per product, not products, and loading every {@code OrderItem} of the day as a JPA
 * object to add its quantities up in Java would be slower and no clearer.
 *
 * @param productId the product as the order line recorded it
 * @param productName the name AS SOLD - order lines keep a copy, so a renamed product still
 *     appears in an old report under the name the customer saw
 * @param unitsSold total quantity across the day's orders
 * @param revenue unit price times quantity, summed
 */
public record TopProduct(long productId, String productName, long unitsSold, BigDecimal revenue) {
}
