package com.ecomdemo.resilience;

import com.ecomdemo.clients.inventory.InventoryGateway;
import com.ecomdemo.shared.ServiceUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * {@link InventoryGateway} with a bulkhead, a circuit breaker and (for reads) a retry around every
 * call, turning "inventory-service cannot answer" into a fast, honest 503 (KI-004).
 *
 * <p>The same policy as {@link ResilientCatalog}, in the same order, for the same reasons, so read
 * that class for the long version:
 * {@code Retry( CircuitBreaker( Bulkhead( HTTP call with a timeout ) ) )}. The timeout is the
 * innermost layer and lives on the HTTP client ({@code InventoryProperties}); without it none of the
 * rest has anything to count.
 *
 * <h2>What differs from the catalogue</h2>
 *
 * <ul>
 *   <li><strong>Only reads are retried.</strong> {@code reserve}, {@code setStockLevel} and
 *       {@code forget} fail fast. A reservation that timed out may already have taken the stock, and
 *       asking twice would take it twice.</li>
 *   <li><strong>{@link #release} never throws</strong>, exactly like the client it wraps: it runs on
 *       a rollback path, where a second exception would hide the first. If the breaker or bulkhead
 *       refuses it, the stock stays reserved and that is logged as loudly as the client logs its own
 *       failures. Phase 32's reconciler is what eventually gives such stock back.</li>
 *   <li><strong>A 409 from {@code reserve} is not an outage.</strong> The client turns it into
 *       {@code InsufficientStockException} before it gets here; it is neither recorded as a failure
 *       nor translated, and the shopper still sees "only 3 left".</li>
 * </ul>
 */
class ResilientInventory implements InventoryGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientInventory.class);

    private static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(1);

    private final InventoryGateway delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    ResilientInventory(InventoryGateway delegate, CircuitBreaker circuitBreaker, Retry retry, Bulkhead bulkhead) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.bulkhead = bulkhead;
    }

    @Override
    public int quantityFor(Long productId) {
        return read(() -> delegate.quantityFor(productId));
    }

    @Override
    public Map<Long, Integer> quantitiesFor(Collection<Long> productIds) {
        return read(() -> delegate.quantitiesFor(productIds));
    }

    @Override
    public void requireAvailable(Long productId, String productName, int quantity) {
        read(() -> {
            delegate.requireAvailable(productId, productName, quantity);
            return null;
        });
    }

    @Override
    public void reserve(Long productId, String productName, int quantity) {
        write(() -> {
            delegate.reserve(productId, productName, quantity);
            return null;
        });
    }

    @Override
    public void release(Long productId, int quantity) {
        try {
            write(() -> {
                delegate.release(productId, quantity);
                return null;
            });
        } catch (ServiceUnavailableException e) {
            log.error(
                    "Could not release {} unit(s) of product {} after a failed checkout: inventory-service "
                            + "is unavailable ({}). That stock is now reserved for an order that does not "
                            + "exist and will stay that way until it is reconciled.",
                    quantity, productId, e.getMessage());
        }
    }

    @Override
    public void setStockLevel(Long productId, int quantity) {
        write(() -> {
            delegate.setStockLevel(productId, quantity);
            return null;
        });
    }

    @Override
    public void forget(Long productId) {
        write(() -> {
            delegate.forget(productId);
            return null;
        });
    }

    private <T> T read(Supplier<T> call) {
        return translated(Retry.decorateSupplier(retry, guarded(call)));
    }

    private <T> T write(Supplier<T> call) {
        return translated(guarded(call));
    }

    /** The two layers every call gets: the bulkhead inside the circuit breaker. */
    private <T> Supplier<T> guarded(Supplier<T> call) {
        return CircuitBreaker.decorateSupplier(circuitBreaker, Bulkhead.decorateSupplier(bulkhead, call));
    }

    /**
     * Every way of "inventory-service cannot serve this" becomes one exception the API maps to
     * 503. Anything else (a 409, a 400, a 404) is not an availability problem and is rethrown
     * untouched.
     */
    private <T> T translated(Supplier<T> decorated) {
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new ServiceUnavailableException(
                    "Stock information is temporarily unavailable. Please try again shortly.", openStateWait(), e);
        } catch (BulkheadFullException e) {
            throw new ServiceUnavailableException(
                    "Stock information is busy. Please try again shortly.", BUSY_RETRY_AFTER, e);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "Stock information did not respond. Please try again shortly.", BUSY_RETRY_AFTER, e);
        }
    }

    /** How long the breaker stays open: the honest answer to "when is it worth asking again?". */
    private Duration openStateWait() {
        long millis = circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
        return Duration.ofMillis(millis);
    }
}
