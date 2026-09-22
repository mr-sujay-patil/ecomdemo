package com.ecomdemo.order;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the checkout log, one row per attempt.
 *
 * <p>This service exists as a separate bean for a reason that is easy to get wrong.
 * {@code @Transactional} works through a proxy: Spring wraps the bean and starts the transaction
 * as the call crosses that wrapper. A call from one method of a class to another method of the
 * <em>same</em> class never crosses it — {@code this.recordAttempt(...)} goes straight to the target
 * object — so the annotation is silently ignored and the "new" transaction is really the caller's
 * own. That is the self-invocation pitfall, and here it would not just be a missed optimisation:
 * the audit row would join the transaction that is about to roll back and vanish with it.
 * Because {@link OrderPlacementService} calls this bean and not itself, the proxy is crossed and
 * {@code REQUIRES_NEW} does what it says.
 */
@Service
public class OrderAuditService {

    private final OrderAuditRepository orderAuditRepository;

    public OrderAuditService(OrderAuditRepository orderAuditRepository) {
        this.orderAuditRepository = orderAuditRepository;
    }

    /**
     * Records one attempt in a transaction of its own.
     *
     * <p>{@code REQUIRES_NEW} suspends whatever transaction is running, opens a second, commits
     * it, and resumes the first. The consequence is the whole point: this row is already durable
     * when the caller's transaction rolls back, so a rejected checkout leaves evidence behind
     * even though it left no order, no stock change and no emptied cart.
     *
     * <p>The method is {@code recordAttempt}, not {@code record}: `record` is a restricted
     * identifier in Java (it names the class kind introduced in 16), and a method that shadows
     * one reads badly at every call site even where the compiler allows it.
     *
     * <p>The cost is honest to state. Two transactions mean two connections held at once, so a
     * pool sized for N concurrent checkouts now needs headroom, and the audit row commits even if
     * the caller later succeeds in a way that contradicts it. For a log, being written too often
     * beats not being written at all.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(OrderOutcome outcome, Long orderId, String detail) {
        orderAuditRepository.save(new OrderAudit(outcome, orderId, detail, Instant.now()));
    }
}
