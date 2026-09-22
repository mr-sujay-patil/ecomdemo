package com.ecomdemo.order;

import com.ecomdemo.common.ConcurrentUpdateException;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.InsufficientStockException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.metrics.CheckoutMetrics;
import com.ecomdemo.metrics.CheckoutOutcome;
import com.ecomdemo.order.dto.OrderResponse;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ecomdemo.security.CurrentUser;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final CurrentUser currentUser;
    private final CheckoutMetrics checkoutMetrics;

    public OrderService(
            OrderRepository orderRepository,
            OrderPlacementService orderPlacementService,
            OrderAuditService orderAuditService,
            CurrentUser currentUser,
            CheckoutMetrics checkoutMetrics) {
        this.orderRepository = orderRepository;
        this.orderPlacementService = orderPlacementService;
        this.orderAuditService = orderAuditService;
        this.currentUser = currentUser;
        this.checkoutMetrics = checkoutMetrics;
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
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderResponse place() {
        Timer.Sample sample = checkoutMetrics.start();
        try {
            OrderResponse placed = placeWithRetries();
            checkoutMetrics.placed(sample, placed.totalAmount());
            return placed;

        // The catch order is not style: ConcurrentUpdateException and InsufficientStockException
        // are both subclasses of ConflictException, so the compiler requires the specific ones
        // first — and if they were not required, putting the general one first would quietly
        // label every failure `empty_cart` and make the tag useless.
        } catch (ConcurrentUpdateException ex) {
            checkoutMetrics.failed(sample, CheckoutOutcome.CONFLICT);
            throw ex;
        } catch (InsufficientStockException ex) {
            checkoutMetrics.failed(sample, CheckoutOutcome.OUT_OF_STOCK);
            throw ex;

        // A plain ConflictException from checkout means the cart was empty; that is the only
        // one placeOnce() raises. The assumption is worth naming, because it is the line that
        // has to change the day a second reason is added — and the failure mode if it is
        // forgotten is silent, not loud: a new rejection reason mislabelled as `empty_cart`
        // still produces a perfectly convincing graph.
        } catch (ConflictException ex) {
            checkoutMetrics.failed(sample, CheckoutOutcome.EMPTY_CART);
            throw ex;

        // Everything unforeseen. Rethrown untouched — instrumentation observes, it does not
        // handle. Swallowing here would turn a 500 into a 200 with no order, which is the one
        // way a metrics change can be worse than no metrics at all.
        } catch (RuntimeException ex) {
            checkoutMetrics.failed(sample, CheckoutOutcome.ERROR);
            throw ex;
        }
    }

    /**
     * The retry loop itself, unchanged since Phase 6 apart from being given a name.
     *
     * <p>Splitting it out is what keeps the timer around <em>all</em> the attempts. Timing each
     * attempt separately would report a checkout that lost two locks and succeeded on the third
     * as three quick checkouts, none of which anybody experienced; what the customer waited for
     * is the whole of this method. It is an ordinary private call, not a proxied one, so it adds
     * no transaction boundary and changes nothing about the behaviour described above.
     */
    private OrderResponse placeWithRetries() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                OrderResponse placed = orderPlacementService.placeOnce();
                orderAuditService.recordAttempt(
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
                    orderAuditService.recordAttempt(OrderOutcome.REJECTED, null, detail);
                    throw new ConcurrentUpdateException(
                            "Another order changed the same products while this one was being "
                                    + "placed. Please try again.");
                }
            }
        }
        throw new IllegalStateException("unreachable: the loop either returns or throws");
    }

    /**
     * The caller's own order history, newest id last.
     *
     * <p><strong>Two layers of authorization, and they are not redundant.</strong> The URL rule
     * in {@code SecurityConfig} already says {@code /api/orders/**} needs a CUSTOMER, so
     * {@code @PreAuthorize} here looks like it repeats it. It does — until this method is called
     * from somewhere that is not that URL: a scheduled job, a message listener, a new controller
     * in Phase 17. A URL rule protects a URL; a method rule protects the method. Method security
     * travels with the code, which is why the sensitive ones carry both.
     *
     * <p>The "only your own" part is not an annotation at all. It is the {@code userId} argument
     * to the query: the rows never leave the database. Filtering after the fact would mean this
     * method had already read other people's orders into memory and was relying on remembering
     * to drop them.
     *
     * <p>Read-only tells Hibernate not to track the loaded entities for changes (no snapshot, no
     * dirty check at flush) and lets the driver mark the connection read-only, which some
     * databases use to route the query to a replica. It is also a statement of intent: a write
     * that sneaks into a read path fails instead of quietly committing.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAllByUserIdWithItems(currentUser.id()).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * One order, if it is yours.
     *
     * <p><strong>Why {@code @PostAuthorize} and not {@code @PreAuthorize}.</strong> The rule is
     * "the order must belong to the caller", and nothing but the id is known before the method
     * runs — whose order it is, is in the row. {@code @PreAuthorize} is evaluated <em>before</em>
     * the call and can only see the arguments and the authentication, so it cannot express this.
     * {@code @PostAuthorize} runs after, with the return value bound to {@code returnObject},
     * which is exactly what the check needs. The price is that the work is already done when the
     * answer turns out to be no: only ever put it on a read. On a method that writes, the write
     * would be rolled back by the exception, but the side effects outside the transaction — a
     * log line, an email, a message on a queue — would not be.
     *
     * <p>{@code authentication.name} is the username Spring Security authenticated, not
     * something from the request, so it cannot be spoofed by the caller.
     *
     * <p><strong>403 or 404?</strong> This answers 403 for somebody else's order, which admits
     * that the order exists. The alternative — filtering by user id in the query and returning
     * 404 — tells the caller nothing, and for something like a private document that matters:
     * "403" on a sequential id is a working existence oracle. Order ids here are already
     * sequential and visible to whoever placed them, and being told plainly "that is not yours"
     * is more useful than a 404 that makes a client think its own order has vanished. It is a
     * judgement call, and it is worth making consciously rather than by accident.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostAuthorize("returnObject.username() == authentication.name")
    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return orderRepository
                .findByIdWithItems(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> NotFoundException.order(id));
    }
}
