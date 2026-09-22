package com.ecomdemo.order;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartItem;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.InsufficientStockException;
import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.Product;
import com.ecomdemo.product.ProductService;
import com.ecomdemo.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One checkout attempt, as one database transaction.
 *
 * <p>Placing an order touches three aggregates: stock goes down on every product, an order and
 * its lines are inserted, and the cart is emptied. Before this phase each of those was its own
 * auto-committed statement, so a crash — or a constraint violation, or a lost update — in the
 * middle left stock already sold with no order to show for it. {@code @Transactional} makes the
 * whole sequence one unit: it all commits, or the database is left exactly as it was found.
 *
 * <p>This is a separate bean from {@link OrderService} on purpose. Spring implements
 * {@code @Transactional} with a proxy, so the transaction begins only when a call arrives from
 * <em>outside</em> the bean. If the retry loop in {@code OrderService} called a transactional
 * method of its own class, the call would never leave the object, the proxy would never be
 * entered, and every attempt would run with no transaction at all — the exact bug this phase is
 * about, hidden behind an annotation that looks right.
 */
@Service
class OrderPlacementService {

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final ProductService productService;
    private final OrderAuditService orderAuditService;
    private final CurrentUser currentUser;
    private final OutboxWriter outbox;

    OrderPlacementService(
            OrderRepository orderRepository,
            CartService cartService,
            ProductService productService,
            OrderAuditService orderAuditService,
            CurrentUser currentUser,
            OutboxWriter outbox) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.productService = productService;
        this.orderAuditService = orderAuditService;
        this.currentUser = currentUser;
        this.outbox = outbox;
    }

    /**
     * Checks stock, reduces it, saves the order and empties the cart — all or nothing.
     *
     * <p>Rollback rules: Spring rolls back on an unchecked exception and commits on a checked
     * one. Every failure here is a {@link RuntimeException}, so the default is what we want; a
     * checked exception would have needed {@code rollbackFor}.
     *
     * <p>The stock of every line is still checked before a single one is written. The
     * transaction would undo a partial reduction anyway, but failing early gives the caller the
     * accurate "3 requested, 2 available" message rather than one about whichever line happened
     * to break first, and it spares the database work it would only have to throw away.
     *
     * <p>What the pre-check cannot do is stop a <em>concurrent</em> checkout from selling the
     * same unit between the check and the write. Nothing inside a single transaction can; that
     * is what {@code Product}'s {@code @Version} column is for, and the failure it raises
     * surfaces here as {@code OptimisticLockingFailureException} at flush or commit time — after
     * this method has returned, which is why {@link OrderService} and not this class handles it.
     */
    @Transactional
    OrderResponse placeOnce() {
        Cart cart = cartService.currentCart();
        try {
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

            // The cart came from currentCart(), which found it by the authenticated user, so
            // the order is stamped with that same account. Neither is a parameter: there is no
            // way for a request to check out somebody else's cart or to place an order in
            // somebody else's name, because neither is ever named in a request.
            Order order = new Order(Instant.now(), currentUser.require());
            for (CartItem line : lines) {
                Product product = line.getProduct();
                order.addItem(
                        product.getId(), product.getName(), product.getPrice(), line.getQuantity());
                product.reduceStock(line.getQuantity());
                productService.save(product);
            }

            Order placed = orderRepository.save(order);
            cartService.clearCart(cart);

            // The event is written to the OUTBOX, in this transaction, as one more insert
            // alongside the order and the stock reduction. That single line is the whole of
            // Phase 18.
            //
            // Phase 17 published a Spring application event here and let an AFTER_COMMIT listener
            // put it on Kafka. The shape was right — nothing announced until the order was real —
            // but it left the dual-write window: commit, then a separate send that a dead broker
            // or a dying process could swallow. It was measured, not feared: four orders lost
            // their notification permanently in that phase's failure test.
            //
            // Now the event commits or rolls back WITH the order, because it is the same
            // transaction and the same database. OutboxRelay does the sending afterwards, from
            // the table. This method still neither knows nor cares that Kafka exists — the
            // difference is that now, neither does the guarantee.
            outbox.append(
                    OrderPlacedEvent.of(
                            placed.getId(),
                            placed.getUsername(),
                            placed.getTotalAmount(),
                            placed.getItems().size(),
                            placed.getPlacedAt()));

            return OrderResponse.from(placed);
        } catch (ConflictException ex) {
            // Written in its own transaction, so it is already committed when this one rolls
            // back a line below. A rejected checkout leaves no trace anywhere else.
            orderAuditService.recordAttempt(OrderOutcome.REJECTED, null, ex.getMessage());
            throw ex;
        }
    }
}
