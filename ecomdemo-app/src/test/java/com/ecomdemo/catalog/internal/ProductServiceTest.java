package com.ecomdemo.catalog.internal;

import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.catalog.Product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.TestData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link ProductService}: no Spring context, no database, no HTTP.
 *
 * <p>{@code MockitoExtension} creates the {@code @Mock} repository and injects it into the
 * {@code @InjectMocks} service through its constructor. Nothing else is loaded, so these tests
 * run in milliseconds and a failure can only mean the service's own logic is wrong — which is
 * the point of the bottom layer of the test pyramid.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final ProductRequest VALID_REQUEST =
            new ProductRequest("Desk Mat", "Stitched-edge felt mat", new BigDecimal("1299.00"), 12, "ACCESSORIES");

    @Mock
    private ProductRepository productRepository;

    /**
     * Since Phase 20 the catalogue does not hold stock; it tells inventory what the level should
     * be. Mocked because what this class is responsible for is making that call correctly - the
     * arithmetic behind it is {@code InventoryServiceTest}'s, against a database.
     */
    @Mock
    private com.ecomdemo.inventory.InventoryService inventory;

    @InjectMocks
    private ProductService productService;

    @Captor
    private ArgumentCaptor<Product> productCaptor;

    @Nested
    @DisplayName("findAll")
    class FindAll {

        @Test
        void findAll_whenProductsExist_returnsOneResponsePerProduct() {
            // Given
            when(productRepository.findAll())
                    .thenReturn(List.of(
                            TestData.product(1L, "Keyboard", "8999.00", 25),
                            TestData.product(2L, "Mouse", "3499.00", 40)));

            // When
            List<ProductResponse> found = productService.findAll();

            // Then
            assertThat(found)
                    .extracting(ProductResponse::id, ProductResponse::name)
                    .containsExactly(tuple(1L, "Keyboard"), tuple(2L, "Mouse"));
        }

        @Test
        void findAll_whenCatalogueIsEmpty_returnsEmptyList() {
            // Given
            when(productRepository.findAll()).thenReturn(List.of());

            // When
            List<ProductResponse> found = productService.findAll();

            // Then
            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        void findById_whenProductExists_returnsEveryField() {
            // Given
            when(productRepository.findById(7L))
                    .thenReturn(Optional.of(TestData.product(7L, "Monitor", "24999.50", 5)));

            // The quantity comes from inventory since Phase 20, not from the product row. The API
            // shape is unchanged, which is the point of asserting it here: a client cannot tell
            // that the number now comes from somewhere else.
            when(inventory.quantityFor(7L)).thenReturn(5);

            // When
            ProductResponse found = productService.findById(7L);

            // Then
            assertThat(found.id()).isEqualTo(7L);
            assertThat(found.name()).isEqualTo("Monitor");
            assertThat(found.price()).isEqualByComparingTo("24999.50");
            assertThat(found.stockQuantity()).isEqualTo(5);
        }

        @Test
        void findById_whenProductIsMissing_throwsNotFound() {
            // Given
            when(productRepository.findById(404L)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> productService.findById(404L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 404 not found");
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        void create_whenCalled_savesTheRequestedProduct() {
            // Given
            when(productRepository.save(any(Product.class))).thenAnswer(saveReturnsItsArgument());

            // When
            productService.create(VALID_REQUEST);

            // Then
            verify(productRepository).save(productCaptor.capture());
            Product saved = productCaptor.getValue();
            assertThat(saved.getName()).isEqualTo("Desk Mat");
            assertThat(saved.getDescription()).isEqualTo("Stitched-edge felt mat");
            assertThat(saved.getPrice()).isEqualByComparingTo("1299.00");

            // Stock is not on the entity since Phase 20. What the catalogue is responsible for is
            // telling inventory the level the request asked for, once the product has an id.
            verify(inventory).setStockLevel(any(), eq(12));
        }

        @Test
        void create_whenCalled_returnsTheSavedProductNotTheRequest() {
            // Given: the database assigns the id, so the response must be built from whatever
            // save() returned, never from the instance the service constructed
            when(productRepository.save(any(Product.class)))
                    .thenReturn(TestData.product(99L, "Desk Mat", "1299.00", 12));

            // When
            ProductResponse created = productService.create(VALID_REQUEST);

            // Then
            assertThat(created.id()).isEqualTo(99L);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        void update_whenProductExists_overwritesEveryFieldAndSaves() {
            // Given
            Product existing = TestData.product(3L, "Old name", "10.00", 1);
            when(productRepository.findById(3L)).thenReturn(Optional.of(existing));
            when(productRepository.save(existing)).thenAnswer(saveReturnsItsArgument());

            // When
            ProductResponse updated = productService.update(3L, VALID_REQUEST);

            // Then
            assertThat(existing.getName()).isEqualTo("Desk Mat");
            assertThat(existing.getDescription()).isEqualTo("Stitched-edge felt mat");
            assertThat(existing.getPrice()).isEqualByComparingTo("1299.00");
            verify(inventory).setStockLevel(3L, 12);
            assertThat(updated.id()).isEqualTo(3L);
            verify(productRepository).save(existing);
        }

        @Test
        void update_whenProductIsMissing_throwsNotFoundAndSavesNothing() {
            // Given
            when(productRepository.findById(404L)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> productService.update(404L, VALID_REQUEST))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 404 not found");
            verify(productRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        void delete_whenProductExists_deletesThatEntity() {
            // Given
            Product existing = TestData.product(3L, "Keyboard", "8999.00", 25);
            when(productRepository.findById(3L)).thenReturn(Optional.of(existing));

            // When
            productService.delete(3L);

            // Then
            verify(productRepository).delete(existing);
        }

        @Test
        void delete_whenProductIsMissing_throwsNotFoundAndDeletesNothing() {
            // Given
            when(productRepository.findById(404L)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> productService.delete(404L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 404 not found");
            verify(productRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("requireProduct and save (the entry points the cart and order features use)")
    class SharedHelpers {

        @Test
        void requireProduct_whenProductExists_returnsTheEntityItself() {
            // Given
            Product existing = TestData.product(3L, "Keyboard", "8999.00", 25);
            when(productRepository.findById(3L)).thenReturn(Optional.of(existing));

            // When
            Product found = productService.requireProduct(3L);

            // Then: the entity, not a DTO — the order feature has to reduce its stock
            assertThat(found).isSameAs(existing);
        }

        @Test
        void requireProduct_whenProductIsMissing_throwsNotFoundNamingTheId() {
            // Given
            when(productRepository.findById(404L)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> productService.requireProduct(404L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 404 not found");
        }


    }

    /** {@code save()} normally returns the managed entity; for most tests the argument will do. */
    private static Answer<Product> saveReturnsItsArgument() {
        return invocation -> invocation.getArgument(0);
    }
}
