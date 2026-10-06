package com.ecomdemo.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.catalog.dto.ProductResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The additive {@code imageUrl} field of {@link ProductResponse} (Phase 34). */
class ProductResponseTest {

    private static Product product(Long id, String imageFile) {
        Product product = new Product("Keyboard", "Tactile", new BigDecimal("8999.00"), "PERIPHERALS");
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "imageFile", imageFile);
        return product;
    }

    @Test
    @DisplayName("a product with an image file gets a path relative to the gateway origin")
    void imageUrlIsAGatewayRelativePath() {
        ProductResponse response = ProductResponse.from(product(7L, "keyboard.svg"), 3);

        assertThat(response.imageUrl()).isEqualTo("/api/products/7/image");
    }

    @Test
    @DisplayName("a product without an image has a null imageUrl, and every other field is as before")
    void noImageMeansNull() {
        ProductResponse response = ProductResponse.from(product(7L, null), 3);

        assertThat(response.imageUrl()).isNull();
        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.name()).isEqualTo("Keyboard");
        assertThat(response.description()).isEqualTo("Tactile");
        assertThat(response.price()).isEqualByComparingTo("8999.00");
        assertThat(response.stockQuantity()).isEqualTo(3);
        assertThat(response.category()).isEqualTo("PERIPHERALS");
    }
}
