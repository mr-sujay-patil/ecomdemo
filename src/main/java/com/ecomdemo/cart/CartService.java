package com.ecomdemo.cart;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.product.Product;
import com.ecomdemo.product.ProductService;
import org.springframework.stereotype.Service;

/** Business rules for the single shared cart. */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final ProductService productService;

    public CartService(CartRepository cartRepository, ProductService productService) {
        this.cartRepository = cartRepository;
        this.productService = productService;
    }

    public CartResponse view() {
        return CartResponse.from(currentCart());
    }

    /** Adding a product already in the cart increases that line rather than duplicating it. */
    public CartResponse addItem(AddCartItemRequest request) {
        Cart cart = currentCart();
        Product product = productService.requireProduct(request.productId());
        cart.addItem(product, request.quantity());
        return CartResponse.from(cartRepository.save(cart));
    }

    public CartResponse updateItem(Long productId, UpdateCartItemRequest request) {
        Cart cart = currentCart();
        CartItem item = cart.findItem(productId).orElseThrow(() -> NotFoundException.cartItem(productId));
        cart.updateQuantity(item, request.quantity());
        return CartResponse.from(cartRepository.save(cart));
    }

    public CartResponse removeItem(Long productId) {
        Cart cart = currentCart();
        CartItem item = cart.findItem(productId).orElseThrow(() -> NotFoundException.cartItem(productId));
        cart.removeItem(item);
        return CartResponse.from(cartRepository.save(cart));
    }

    /**
     * The cart the whole application shares, created on first use.
     *
     * <p>Public because the order feature checks out this exact instance; it still goes through
     * the service rather than reaching for {@code CartRepository} directly, so the "one cart"
     * rule stays in one place.
     */
    public Cart currentCart() {
        return cartRepository.findCart().orElseGet(() -> cartRepository.save(new Cart()));
    }

    /** Called by the order feature once an order has been placed. */
    public void clearCart(Cart cart) {
        cart.clear();
        cartRepository.save(cart);
    }
}
