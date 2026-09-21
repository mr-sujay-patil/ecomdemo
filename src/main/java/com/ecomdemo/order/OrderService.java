package com.ecomdemo.order;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartItem;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.InsufficientStockException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.Product;
import com.ecomdemo.product.ProductService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Checkout and order history. */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final ProductService productService;

    public OrderService(
            OrderRepository orderRepository, CartService cartService, ProductService productService) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.productService = productService;
    }

    /**
     * Places an order from the shared cart: check stock, reduce stock, save the order, empty
     * the cart.
     *
     * <p>Every line is checked <em>before</em> anything is written, so a cart whose second line
     * is short of stock does not leave the first line's stock already decremented.
     *
     * <p>This is still not atomic. Each repository call commits on its own, so a crash midway
     * through the write loop would leave stock reduced with no order to show for it, and two
     * simultaneous checkouts can both pass the stock check and oversell. Phase 6 closes both
     * holes with {@code @Transactional} and optimistic locking; leaving the gap visible here is
     * deliberate, because it is what makes that phase worth doing.
     */
    public OrderResponse place() {
        Cart cart = cartService.currentCart();
        if (cart.isEmpty()) {
            throw new ConflictException("Cannot place an order: the cart is empty");
        }

        List<CartItem> lines = cart.getItems();
        for (CartItem line : lines) {
            Product product = line.getProduct();
            if (!product.hasStockFor(line.getQuantity())) {
                throw new InsufficientStockException(
                        product.getName(), line.getQuantity(), product.getStockQuantity());
            }
        }

        Order order = new Order(Instant.now());
        for (CartItem line : lines) {
            Product product = line.getProduct();
            order.addItem(product.getId(), product.getName(), product.getPrice(), line.getQuantity());
            product.reduceStock(line.getQuantity());
            productService.save(product);
        }

        Order placed = orderRepository.save(order);
        cartService.clearCart(cart);
        return OrderResponse.from(placed);
    }

    public List<OrderResponse> findAll() {
        return orderRepository.findAllWithItems().stream().map(OrderResponse::from).toList();
    }

    public OrderResponse findById(Long id) {
        return orderRepository
                .findByIdWithItems(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> NotFoundException.order(id));
    }
}
