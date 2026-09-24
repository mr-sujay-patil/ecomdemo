package com.ecomdemo.support;

import com.ecomdemo.catalog.Product;
import java.math.BigDecimal;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builders for the entities this service owns.
 *
 * <p>Split out of the application's {@code TestData} in Phase 20c: that class built products,
 * users, carts and orders, and after the extraction no single module owns all four. Only the
 * product half lives here, and it is the only half catalog-service can construct.
 */
public final class TestData {

    private TestData() {
    }

    /**
     * A product with a fixed id, so an assertion can name one without a database round trip.
     *
     * <p><strong>The {@code stockQuantity} parameter is gone</strong>, and its absence is the split
     * in one signature. The application's version still took one and silently ignored it — a
     * leftover from 20a, when stock moved to its own table but the builders had not caught up. A
     * parameter that documents intent and changes nothing is worse than none: it reads like a
     * setup step. Tests that need stock now say so against inventory-service, which owns it.
     */
    public static Product product(long id, String name, String price) {
        Product product = new Product(name, name + " description", new BigDecimal(price));
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}
