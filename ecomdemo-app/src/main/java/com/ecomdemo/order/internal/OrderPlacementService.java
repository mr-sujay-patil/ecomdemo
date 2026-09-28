package com.ecomdemo.order.internal;

import com.ecomdemo.order.Order;
import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartItem;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.messaging.OrderCreatedEvent;
import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.clients.inventory.InventoryGateway;
import com.ecomdemo.jwt.CurrentUser;
import java.time.Instant;
import java.util.ArrayList;
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
 * <p>(Phase 6's words. Since Phase 24 the stock is no longer one of the things this transaction
 * changes - it starts a saga instead; see {@link #placeOnce()}. The single-transaction rule now
 * covers the order, the cart and the outbox row that starts the saga.)
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
    private final InventoryGateway inventory;
    private final OrderAuditService orderAuditService;
    private final CurrentUser currentUser;
    private final OutboxWriter outbox;

    OrderPlacementService(
            OrderRepository orderRepository,
            CartService cartService,
            InventoryGateway inventory,
            OrderAuditService orderAuditService,
            CurrentUser currentUser,
            OutboxWriter outbox) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.inventory = inventory;
        this.orderAuditService = orderAuditService;
        this.currentUser = currentUser;
        this.outbox = outbox;
    }

    /**
     * Checks stock, saves the order as PENDING, empties the cart and starts the saga — all or
     * nothing.
     *
     * <p>Rollback rules: Spring rolls back on an unchecked exception and commits on a checked
     * one. Every failure here is a {@link RuntimeException}, so the default is what we want; a
     * checked exception would have needed {@code rollbackFor}.
     *
     * <h2>Phase 24: this method no longer takes any stock</h2>
     *
     * <p>Since Phase 20b it reserved each line over HTTP and, if its own transaction then rolled
     * back, gave the stock back with an after-rollback hook - a compensation that could be lost if
     * this process died in between, and that nothing reconciled. The saga replaces all of it.
     * Checkout now does only what is local to this service - the order row, the cart, and one
     * outbox row, {@code OrderCreatedEvent} - and commits them together. inventory-service
     * reserves the stock when it reads that event, payment-service charges for it, and their
     * replies move the order to CONFIRMED or CANCELLED ({@code OrderSagaHandler}). There is no
     * remote write left inside this transaction, so there is nothing left to compensate here.
     *
     * <p><strong>The stock pre-check stays</strong>, and it is a read. It is what lets a shopper
     * who asks for more than exists get an immediate "3 requested, 2 available" (409) instead of
     * a PENDING order that is cancelled a second later. It can still be wrong - another checkout
     * may take the last unit between this check and inventory's reservation - and then the saga
     * cancels the order with the same message. The pre-check is a courtesy; the reservation is the
     * guarantee.
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
                inventory.requireAvailable(
                        line.getProductId(), line.getProductName(), line.getQuantity());
            }

            // The cart came from currentCart(), which found it by the authenticated user, so
            // the order is stamped with that same account. Neither is a parameter: there is no
            // way for a request to check out somebody else's cart or to place an order in
            // somebody else's name, because neither is ever named in a request.
            Order order = new Order(Instant.now(), currentUser.id(), currentUser.username());

            List<OrderCreatedEvent.Line> reservation = new ArrayList<>();
            for (CartItem line : lines) {
                // Both the order line and the line to reserve are built from the CART's snapshot,
                // not from the catalogue. Checkout does not read a product at all - the price
                // charged is the price the shopper was shown.
                order.addItem(
                        line.getProductId(), line.getProductName(), line.getUnitPrice(),
                        line.getQuantity());
                reservation.add(new OrderCreatedEvent.Line(
                        line.getProductId(), line.getProductName(), line.getQuantity()));
            }

            Order placed = orderRepository.save(order);
            cartService.clearCart(cart);

            // The saga starts HERE, as one more insert in this transaction - Phase 18's outbox,
            // now carrying the first step of a distributed transaction instead of a notification.
            // If this transaction rolls back, the event never existed and inventory never hears
            // of the order; if it commits, the relay will deliver it, however long Kafka is down.
            outbox.append(
                    OrderCreatedEvent.of(
                            placed.getId(),
                            placed.getUsername(),
                            placed.getTotalAmount(),
                            reservation,
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
