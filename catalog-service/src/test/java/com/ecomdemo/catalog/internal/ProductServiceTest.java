package com.ecomdemo.catalog.internal;

import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.catalog.Product;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.clients.catalog.ProductUpsert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
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
    private com.ecomdemo.clients.inventory.InventoryClient inventory;

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
                            TestData.product(1L, "Keyboard", "8999.00"),
                            TestData.product(2L, "Mouse", "3499.00")));

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
                    .thenReturn(Optional.of(TestData.product(7L, "Monitor", "24999.50")));

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
                    .thenReturn(TestData.product(99L, "Desk Mat", "1299.00"));

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
            Product existing = TestData.product(3L, "Old name", "10.00");
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
            Product existing = TestData.product(3L, "Keyboard", "8999.00");
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
            Product existing = TestData.product(3L, "Keyboard", "8999.00");
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

    @Nested
    @DisplayName("bulk upsert, for the CSV import in another service")
    class UpsertAll {

        /**
         * THIS TEST CAME FROM ANOTHER MODULE. It used to live in {@code ProductImportProcessorTest}
         * in the application, where the import looked a product up by name before saving it. Doing
         * that across a network would be one HTTP call per ROW, so the resolution moved here — and
         * the test that guards it had to move with the behaviour, or the idempotency of a re-import
         * would have quietly stopped being covered by anything.
         */
        @Test
        @DisplayName("a null id resolves by name, so a re-import updates rather than duplicates")
        void nullIdResolvesByName() {
            Product existing = TestData.product(3L, "Widget", "1.00");
            when(productRepository.findFirstByNameOrderByIdAsc("Widget"))
                    .thenReturn(Optional.of(existing));
            when(productRepository.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));

            List<ProductSnapshot> saved = productService.upsertAll(List.of(
                    new ProductUpsert(null, "Widget", "new description", new BigDecimal("12.50"), "TEST")));

            assertThat(saved).singleElement().satisfies(product -> {
                assertThat(product.id()).as("the existing product, not a second one").isEqualTo(3L);
                assertThat(product.description()).isEqualTo("new description");
                assertThat(product.price()).isEqualByComparingTo("12.50");
            });
        }

        @Test
        @DisplayName("a name nothing matches becomes a new product with no id yet")
        void unknownNameCreates() {
            when(productRepository.findFirstByNameOrderByIdAsc("Fresh")).thenReturn(Optional.empty());
            when(productRepository.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));

            List<ProductSnapshot> saved = productService.upsertAll(List.of(
                    new ProductUpsert(null, "Fresh", "brand new", new BigDecimal("9.99"), "TEST")));

            assertThat(saved).singleElement().satisfies(product -> {
                assertThat(product.id()).as("the database assigns it on flush").isNull();
                assertThat(product.name()).isEqualTo("Fresh");
            });
        }

        @Test
        @DisplayName("stockQuantity comes back null, because the catalogue was never asked")
        void doesNotInventAStockNumber() {
            when(productRepository.findFirstByNameOrderByIdAsc("Fresh")).thenReturn(Optional.empty());
            when(productRepository.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));

            List<ProductSnapshot> saved = productService.upsertAll(List.of(
                    new ProductUpsert(null, "Fresh", "brand new", new BigDecimal("9.99"), "TEST")));

            // The import sets stock through its OWN call to inventory-service. A number invented
            // here would be a guess about another service's data; null says "not asked".
            assertThat(saved).singleElement()
                    .extracting(ProductSnapshot::stockQuantity)
                    .isNull();
        }
    }

}
