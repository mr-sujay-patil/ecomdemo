package com.ecomdemo.order;

import com.ecomdemo.common.ConcurrentUpdateException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.order.dto.OrderResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Checkout and order history. */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /**
     * How many times a checkout may be retried after losing an optimistic lock.
     *
     * <p>Small on purpose. A retry only helps when the conflict was momentary — someone else
     * bought a different unit of the same product a millisecond earlier. If three attempts in a
     * row all lose, the contention is not momentary and retrying harder would only hold
     * connections open while the client waits. Three is the usual starting point: it absorbs the
     * one-off collision that optimistic locking is designed for, and gives up quickly on the
     * stampede it is not.
     */
    static final int MAX_ATTEMPTS = 3;

    private final OrderRepository orderRepository;
    private final OrderPlacementService orderPlacementService;
    private final OrderAuditService orderAuditService;

    public OrderService(
            OrderRepository orderRepository,
            OrderPlacementService orderPlacementService,
            OrderAuditService orderAuditService) {
        this.orderRepository = orderRepository;
        this.orderPlacementService = orderPlacementService;
        this.orderAuditService = orderAuditService;
    }

    /**
     * Places an order, retrying a limited number of times if another checkout got there first.
     *
     * <p>This method is deliberately <strong>not</strong> {@code @Transactional}. Retrying inside
     * a transaction is pointless: once a flush has failed the persistence context is unusable and
     * the transaction is already marked rollback-only, so a second attempt would fail on the way
     * out no matter how correct it was. Each attempt therefore needs a transaction of its own,
     * which is exactly what calling into another bean — {@link OrderPlacementService} — provides.
     * It also means the conflict cannot be seen until {@code placeOnce()} <em>returns</em>: the
     * versioned UPDATE runs at commit, inside the proxy, after the method body is done.
     *
     * <p>An exhausted retry budget is a 409, not a 500. Nothing is broken; the client simply
     * asked for a unit somebody else was buying at that moment, and trying again later is a
     * reasonable thing for it to do.
     */
    public OrderResponse place() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                OrderResponse placed = orderPlacementService.placeOnce();
                orderAuditService.record(
                        OrderOutcome.PLACED,
                        placed.id(),
                        "Order placed with %d line(s), total %s"
                                .formatted(placed.items().size(), placed.totalAmount()));
                return placed;
            } catch (OptimisticLockingFailureException ex) {
                log.warn(
                        "Checkout attempt {} of {} lost an optimistic lock: {}",
                        attempt, MAX_ATTEMPTS, ex.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    String detail =
                            "Gave up after %d concurrent-update conflicts".formatted(MAX_ATTEMPTS);
                    orderAuditService.record(OrderOutcome.REJECTED, null, detail);
                    throw new ConcurrentUpdateException(
                            "Another order changed the same products while this one was being "
                                    + "placed. Please try again.");
                }
            }
        }
        throw new IllegalStateException("unreachable: the loop either returns or throws");
    }

    /**
     * Read-only. It tells Hibernate not to bother tracking the loaded entities for changes (no
     * snapshot, no dirty check at flush) and lets the driver mark the connection read-only, which
     * some databases use to route the query to a replica. It is also a statement of intent: a
     * write that sneaks into a read path fails instead of quietly committing.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAllWithItems().stream().map(OrderResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return orderRepository
                .findByIdWithItems(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> NotFoundException.order(id));
    }
}
