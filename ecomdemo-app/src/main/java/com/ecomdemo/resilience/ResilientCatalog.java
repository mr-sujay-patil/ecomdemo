package com.ecomdemo.resilience;

import com.ecomdemo.clients.catalog.CatalogGateway;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.clients.catalog.ProductUpsert;
import com.ecomdemo.clients.catalog.ProductWrite;
import com.ecomdemo.shared.ServiceUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * {@link CatalogGateway} with a bulkhead, a circuit breaker and (for reads) a retry around every
 * call, turning "catalog-service cannot answer" into a fast, honest 503.
 *
 * <h2>The order of the layers is the design</h2>
 *
 * <pre>
 *   Retry( CircuitBreaker( Bulkhead( HTTP call with a timeout ) ) )
 * </pre>
 *
 * <ul>
 *   <li><strong>Timeout, innermost</strong> — on the socket, set in {@code CatalogProperties}.
 *       Without it none of the rest works: a call that never returns is never counted as a
 *       failure, and the breaker never learns anything.</li>
 *   <li><strong>Bulkhead</strong> — at most N calls to catalog-service in flight at once. If
 *       catalog-service becomes slow, the requests waiting on it can take N threads and no more;
 *       the rest of the application (orders, carts, logins through other paths) keeps its
 *       threads. Excess calls are refused at once rather than queued.</li>
 *   <li><strong>Circuit breaker</strong> — watches the outcomes of the last N calls. When too
 *       many failed, it OPENS and refuses every call immediately, without touching the network,
 *       for a fixed wait. That is what makes a dead dependency cost microseconds instead of a
 *       timeout per request, and it gives the dependency room to recover instead of being
 *       hammered while it restarts. After the wait it goes HALF_OPEN, lets a few trial calls
 *       through, and closes again if they succeed.</li>
 *   <li><strong>Retry, outermost</strong> — each attempt goes through the breaker, so every
 *       failed attempt is counted and an open breaker stops the retrying too. Retry sits outside
 *       so that it can never retry PAST an open breaker: {@link CallNotPermittedException} is not
 *       in its retry list.</li>
 * </ul>
 *
 * <h2>Only reads are retried</h2>
 *
 * A read that timed out can be repeated safely. A create that timed out may have succeeded on the
 * far side — the request arrived, the response did not — and repeating it makes a second product.
 * So writes get the bulkhead and the breaker, and fail fast, but are never retried here.
 *
 * <h2>What is a failure</h2>
 *
 * Only "catalog-service did not answer properly": an I/O error or timeout
 * ({@link ResourceAccessException}) or a 5xx ({@link HttpServerErrorException}). A 404 for an
 * unknown product is catalog-service working perfectly, and must neither open the breaker nor be
 * retried; it arrives here as {@code NotFoundException} and passes straight through. That list is
 * configuration ({@code resilience4j.*.instances.catalog.*-exceptions}), not code, and
 * {@code ResilientCatalogTest} runs against the real configuration to keep it honest.
 */
class ResilientCatalog implements CatalogGateway {

    private static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(1);

    private final CatalogGateway delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    ResilientCatalog(CatalogGateway delegate, CircuitBreaker circuitBreaker, Retry retry, Bulkhead bulkhead) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.bulkhead = bulkhead;
    }

    @Override
    public List<ProductSnapshot> findAll() {
        return read(delegate::findAll);
    }

    @Override
    public ProductSnapshot requireProduct(Long productId) {
        return read(() -> delegate.requireProduct(productId));
    }

    @Override
    public ProductSnapshot create(ProductWrite product) {
        return write(() -> delegate.create(product));
    }

    @Override
    public ProductSnapshot update(Long productId, ProductWrite product) {
        return write(() -> delegate.update(productId, product));
    }

    @Override
    public void delete(Long productId) {
        write(() -> {
            delegate.delete(productId);
            return null;
        });
    }

    @Override
    public List<ProductSnapshot> upsertAll(List<ProductUpsert> products) {
        return write(() -> delegate.upsertAll(products));
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
     * The fallback: every way of "catalog-service cannot serve this" becomes one exception the
     * API maps to 503, with a message a shopper can read and a {@code Retry-After} that means
     * something. Anything else — a 404, a 400 for a bad write — is not an availability problem
     * and is rethrown untouched.
     */
    private <T> T translated(Supplier<T> decorated) {
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new ServiceUnavailableException(
                    "The product catalogue is temporarily unavailable. Please try again shortly.",
                    openStateWait(),
                    e);
        } catch (BulkheadFullException e) {
            throw new ServiceUnavailableException(
                    "The product catalogue is busy. Please try again shortly.", BUSY_RETRY_AFTER, e);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new ServiceUnavailableException(
                    "The product catalogue did not respond. Please try again shortly.", BUSY_RETRY_AFTER, e);
        }
    }

    /** How long the breaker stays open: the honest answer to "when is it worth asking again?". */
    private Duration openStateWait() {
        long millis = circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
        return Duration.ofMillis(millis);
    }
}
