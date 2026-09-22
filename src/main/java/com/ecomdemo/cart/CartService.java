package com.ecomdemo.cart;

import com.ecomdemo.cart.internal.CartRepository;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.catalog.Product;
import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.customer.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules for the caller's own cart.
 *
 * <p>Every method here runs in a read-write transaction, including {@link #view()}. That looks
 * wrong for a read until you notice that the cart is created on first use: the very first GET
 * inserts a row. A {@code readOnly = true} transaction puts Hibernate into manual flush mode, so
 * that insert would be prepared and then silently dropped. The read paths that really only read
 * are on {@code ProductService} and {@code OrderService}.
 *
 * <p>Transactions also fixed something that used to be worked around here. Before this phase
 * each repository call committed on its own, so the cart handed back by {@code save()} was a
 * freshly merged copy with uninitialised lazy associations and a closed session behind it, and
 * the service had to re-read the whole cart to render a response. Inside one transaction the
 * entity stays managed and the session stays open, so the round trip is gone.
 */
@Service
@Transactional
public class CartService {

    private final CartRepository cartRepository;
    private final ProductService productService;
    private final CurrentUser currentUser;

    public CartService(
            CartRepository cartRepository, ProductService productService, CurrentUser currentUser) {
        this.cartRepository = cartRepository;
        this.productService = productService;
        this.currentUser = currentUser;
    }

    public CartResponse view() {
        return CartResponse.from(currentCart());
    }

    /**
     * Saves the cart and maps that same instance to a response.
     *
     * <p>It used to save and then re-read the whole cart. Outside a transaction it had to: each
     * repository call committed on its own, so the cart passed to {@code save()} was detached and
     * Spring Data performed a <em>merge</em> — loading a fresh managed copy and returning that,
     * with uninitialised lazy proxies and a session that closed before anything could read a
     * product name. Inside one transaction the instance stays managed and the session stays open,
     * so mapping it directly is correct and the extra query is gone.
     *
     * <p>{@code save()} itself is now barely a call: Hibernate's dirty checking would flush these
     * changes at commit with or without it. It stays because it says plainly where the write is.
     */
    private CartResponse saveAndView(Cart cart) {
        cartRepository.save(cart);
        return CartResponse.from(cart);
    }

    /** Adding a product already in the cart increases that line rather than duplicating it. */
    public CartResponse addItem(AddCartItemRequest request) {
        Cart cart = currentCart();
        Product product = productService.requireProduct(request.productId());
        cart.addItem(product, request.quantity());
        return saveAndView(cart);
    }

    public CartResponse updateItem(Long productId, UpdateCartItemRequest request) {
        Cart cart = currentCart();
        CartItem item = cart.findItem(productId).orElseThrow(() -> NotFoundException.cartItem(productId));
        cart.updateQuantity(item, request.quantity());
        return saveAndView(cart);
    }

    public CartResponse removeItem(Long productId) {
        Cart cart = currentCart();
        CartItem item = cart.findItem(productId).orElseThrow(() -> NotFoundException.cartItem(productId));
        cart.removeItem(item);
        return saveAndView(cart);
    }

    /**
     * The authenticated account's cart, created on first use.
     *
     * <p>This method is where the whole of the cart's access control lives, and it is worth
     * being clear about why that is enough. No endpoint takes a cart id: there is no
     * {@code GET /api/cart/{id}} to guess at, so a caller cannot ask for someone else's cart in
     * the first place. Every cart operation starts here, and here the owner is not a parameter
     * either — it is read from the authenticated principal, which the client has no say over.
     * An access-control check that cannot be reached with the wrong argument is better than one
     * that has to be remembered in five places.
     *
     * <p>Public because the order feature checks out this exact instance; it still goes through
     * the service rather than reaching for {@code CartRepository} directly, so the "one cart per
     * account" rule stays in one place.
     *
     * <p>It is called from inside {@code OrderPlacementService}'s transaction as well as from
     * this class's own. Propagation is {@code REQUIRED}, the default: an existing transaction is
     * joined rather than a second one started, so the cart the order reads and the cart it later
     * empties are the same managed instance in the same unit of work.
     */
    public Cart currentCart() {
        return cartRepository
                .findByUserId(currentUser.id())
                .orElseGet(() -> cartRepository.save(new Cart(currentUser.require())));
    }

    /** Called by the order feature once an order has been placed. */
    public void clearCart(Cart cart) {
        cart.clear();
        cartRepository.save(cart);
    }
}
