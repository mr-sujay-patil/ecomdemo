package com.ecomdemo.cart.internal;

import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.Cart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.customer.User;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.clients.catalog.CatalogGateway;
import com.ecomdemo.customer.CurrentUser;
import com.ecomdemo.support.TestData;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CartService}.
 *
 * <p>Two collaborators are mocked here, and they are mocked for different reasons.
 * {@code CartRepository} is replaced to keep the database out; {@code CatalogGateway} is
 * replaced because this test is about cart rules, and a real product lookup would drag its own
 * repository in with it. Mocking at the service boundary is what keeps a failure here pointing
 * at {@code CartService} and nothing else.
 *
 * <p>{@code findByUserId()} is stubbed once and returns the same instance every time, which is
 * what a transaction would give the service: inside one unit of work the cart is a managed
 * entity, so every lookup and the save that follows are all the same object.
 *
 * <p>{@code CurrentUser} is mocked too, and that is the reason it exists as a bean rather than
 * as a static call to {@code SecurityContextHolder}. A static thread-local would have to be
 * populated and torn down around every test here; an injected collaborator is simply stubbed
 * like any other.
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CatalogGateway catalogue;

    @Mock
    private CurrentUser currentUser;

    @InjectMocks
    private CartService cartService;

    private static final User OWNER = TestData.customer();

    /**
     * Stubs "who is calling" and "what is in their cart" together, because in this service they
     * are always used together: the cart is found BY the caller.
     */
    private void givenTheCallersCartIs(Cart cart) {
        when(currentUser.id()).thenReturn(OWNER.getId());
        when(cartRepository.findByUserId(OWNER.getId())).thenReturn(Optional.of(cart));
    }

    @Nested
    @DisplayName("view")
    class View {

        @Test
        void view_whenTheCartHasLines_returnsThemWithAServerCalculatedTotal() {
            // Given: 2 x 1500.00 plus 3 x 100.50
            Cart cart = TestData.cart(1L);
            TestData.addTo(cart, TestData.product(10L, "Lamp", "1500.00"), 2);
            TestData.addTo(cart, TestData.product(11L, "Cable", "100.50"), 3);
            givenTheCallersCartIs(cart);

            // When
            CartResponse view = cartService.view();

            // Then
            assertThat(view.id()).isEqualTo(1L);
            assertThat(view.items())
                    .extracting(CartItemResponse::productId, CartItemResponse::quantity)
                    .containsExactly(tuple(10L, 2), tuple(11L, 3));
            assertThat(view.totalAmount()).isEqualByComparingTo("3301.50");
        }

        @Test
        void view_whenTheCartIsEmpty_returnsNoLinesAndAZeroTotal() {
            // Given
            givenTheCallersCartIs(TestData.cart(1L));

            // When
            CartResponse view = cartService.view();

            // Then
            assertThat(view.items()).isEmpty();
            assertThat(view.totalAmount()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("addItem")
    class AddItem {

        @Test
        void addItem_whenTheProductIsNotYetInTheCart_addsANewLine() {
            // Given
            Cart cart = TestData.cart(1L);
            ProductSnapshot product = TestData.product(10L, "Lamp", "1500.00");
            givenTheCallersCartIs(cart);
            when(catalogue.requireProduct(10L)).thenReturn(product);

            // When
            CartResponse view = cartService.addItem(new AddCartItemRequest(10L, 2));

            // Then
            assertThat(view.items()).hasSize(1);
            assertThat(view.items().getFirst().quantity()).isEqualTo(2);
            assertThat(view.totalAmount()).isEqualByComparingTo("3000.00");
            verify(cartRepository).save(cart);
        }

        @Test
        void addItem_whenTheProductIsAlreadyInTheCart_increasesThatLineInsteadOfDuplicatingIt() {
            // Given: the cart already holds 2 of product 10
            ProductSnapshot product = TestData.product(10L, "Lamp", "1500.00");
            Cart cart = TestData.cartWith(1L, product, 2);
            givenTheCallersCartIs(cart);
            when(catalogue.requireProduct(10L)).thenReturn(product);

            // When
            CartResponse view = cartService.addItem(new AddCartItemRequest(10L, 3));

            // Then
            assertThat(view.items()).hasSize(1);
            assertThat(view.items().getFirst().quantity()).isEqualTo(5);
            assertThat(view.totalAmount()).isEqualByComparingTo("7500.00");
        }

        @Test
        void addItem_whenTheProductDoesNotExist_throwsNotFoundAndSavesNothing() {
            // Given
            givenTheCallersCartIs(TestData.cart(1L));
            when(catalogue.requireProduct(404L)).thenThrow(NotFoundException.product(404L));

            // When / Then
            AddCartItemRequest request = new AddCartItemRequest(404L, 1);
            assertThatThrownBy(() -> cartService.addItem(request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 404 not found");
            verify(cartRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateItem")
    class UpdateItem {

        @Test
        void updateItem_whenTheLineExists_replacesTheQuantity() {
            // Given
            ProductSnapshot product = TestData.product(10L, "Lamp", "1500.00");
            Cart cart = TestData.cartWith(1L, product, 2);
            givenTheCallersCartIs(cart);

            // When
            CartResponse view = cartService.updateItem(10L, new UpdateCartItemRequest(5));

            // Then: replaced, not added to
            assertThat(view.items().getFirst().quantity()).isEqualTo(5);
            assertThat(view.totalAmount()).isEqualByComparingTo("7500.00");
            verify(cartRepository).save(cart);
        }

        @Test
        void updateItem_whenTheProductIsNotInTheCart_throwsNotFoundAndSavesNothing() {
            // Given
            givenTheCallersCartIs(TestData.cart(1L));

            // When / Then
            UpdateCartItemRequest request = new UpdateCartItemRequest(5);
            assertThatThrownBy(() -> cartService.updateItem(10L, request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 10 is not in the cart");
            verify(cartRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("removeItem")
    class RemoveItem {

        @Test
        void removeItem_whenTheLineExists_dropsItAndLeavesTheOthers() {
            // Given
            Cart cart = TestData.cart(1L);
            TestData.addTo(cart, TestData.product(10L, "Lamp", "1500.00"), 2);
            TestData.addTo(cart, TestData.product(11L, "Cable", "100.50"), 3);
            givenTheCallersCartIs(cart);

            // When
            CartResponse view = cartService.removeItem(10L);

            // Then
            assertThat(view.items())
                    .extracting(CartItemResponse::productId)
                    .containsExactly(11L);
            assertThat(view.totalAmount()).isEqualByComparingTo("301.50");
            verify(cartRepository).save(cart);
        }

        @Test
        void removeItem_whenTheProductIsNotInTheCart_throwsNotFoundAndSavesNothing() {
            // Given
            givenTheCallersCartIs(TestData.cart(1L));

            // When / Then
            assertThatThrownBy(() -> cartService.removeItem(10L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 10 is not in the cart");
            verify(cartRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("currentCart and clearCart (the entry points the order feature uses)")
    class SharedHelpers {

        @Test
        void currentCart_whenACartAlreadyExists_returnsItWithoutCreatingAnother() {
            // Given
            Cart existing = TestData.cart(1L);
            givenTheCallersCartIs(existing);

            // When
            Cart found = cartService.currentCart();

            // Then
            assertThat(found).isSameAs(existing);
            verify(cartRepository, never()).save(any());
        }

        @Test
        void currentCart_whenNoCartExistsYet_createsAndSavesOneForTheCaller() {
            // Given
            Cart created = TestData.cart(1L);
            when(currentUser.id()).thenReturn(OWNER.getId());
            when(currentUser.require()).thenReturn(OWNER);
            when(cartRepository.findByUserId(OWNER.getId())).thenReturn(Optional.empty());
            when(cartRepository.save(any(Cart.class))).thenReturn(created);

            // When
            Cart found = cartService.currentCart();

            // Then
            assertThat(found).isSameAs(created);
            verify(cartRepository).save(any(Cart.class));
        }

        @Test
        void clearCart_whenCalled_emptiesTheCartAndSavesIt() {
            // Given
            Cart cart = TestData.cartWith(1L, TestData.product(10L, "Lamp", "1500.00"), 2);

            // When
            cartService.clearCart(cart);

            // Then
            assertThat(cart.isEmpty()).isTrue();
            verify(cartRepository).save(cart);
        }
    }
}
