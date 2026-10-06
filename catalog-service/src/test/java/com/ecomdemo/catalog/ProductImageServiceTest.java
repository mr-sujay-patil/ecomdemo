package com.ecomdemo.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ecomdemo.catalog.internal.ProductRepository;
import com.ecomdemo.shared.NotFoundException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link ProductImageService}: which file a product maps to, how it is typed, and
 * the guards that stop a database value from becoming a path into the filesystem.
 */
@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock
    private ProductRepository repository;

    private ProductImageService service;

    @BeforeEach
    void setUp() {
        service = new ProductImageService(repository);
    }

    private void productWithImage(long id, String imageFile) {
        Product product = new Product("Keyboard", "Tactile", new BigDecimal("8999.00"));
        ReflectionTestUtils.setField(product, "imageFile", imageFile);
        when(repository.findById(id)).thenReturn(Optional.of(product));
    }

    @Test
    @DisplayName("a seeded product's file is returned with its media type and a stable strong ETag")
    void returnsTheFile() {
        productWithImage(1L, "mechanical-keyboard.svg");

        ProductImage first = service.find(1L);
        ProductImage second = service.find(1L);

        assertThat(first.mediaType()).isEqualTo(MediaType.valueOf("image/svg+xml"));
        assertThat(first.content()).isNotEmpty();
        assertThat(new String(first.content())).contains("<svg");
        assertThat(first.etag()).matches("\"[0-9a-f]{64}\"");
        assertThat(second.etag()).isEqualTo(first.etag());
    }

    @Test
    @DisplayName("an unknown product is a 404")
    void unknownProduct() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("a product with no image is a 404")
    void productWithoutImage() {
        productWithImage(2L, null);

        assertThatThrownBy(() -> service.find(2L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("no image");
    }

    @Test
    @DisplayName("a stored value that is a path, or has a type outside the allow-list, is refused, never opened")
    void refusesAnythingButAPlainAllowListedFileName() {
        for (String bad : new String[] {"../application.properties", "a/b.svg", "..\\b.svg", "x.exe", "noextension", "x.svg.exe"}) {
            productWithImage(3L, bad);
            assertThatThrownBy(() -> service.find(3L))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a file name that is allowed but ships no file is a server fault, not a 404")
    void missingFileIsAServerFault() {
        productWithImage(4L, "does-not-exist.svg");

        assertThatThrownBy(() -> service.find(4L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the allow-list maps every permitted extension to an image media type")
    void allowList() {
        assertThat(ProductImageService.mediaTypeFor("a.svg")).hasValue(MediaType.valueOf("image/svg+xml"));
        assertThat(ProductImageService.mediaTypeFor("a.png")).hasValue(MediaType.IMAGE_PNG);
        assertThat(ProductImageService.mediaTypeFor("a.webp")).hasValue(MediaType.valueOf("image/webp"));
        assertThat(ProductImageService.mediaTypeFor("a.jpg")).hasValue(MediaType.IMAGE_JPEG);
        assertThat(ProductImageService.mediaTypeFor("a.jpeg")).hasValue(MediaType.IMAGE_JPEG);
        assertThat(ProductImageService.mediaTypeFor("a.gif")).isEmpty();
        assertThat(ProductImageService.mediaTypeFor("a.html")).isEmpty();
    }
}
