package com.ecomdemo.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecomdemo.clients.catalog.CatalogClient;
import com.ecomdemo.clients.catalog.CatalogGateway;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.shared.ServiceUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * The resilience policy around catalog-service, run against the SHIPPED configuration.
 *
 * <p>The context is Resilience4j's own Boot auto-configuration, fed the {@code resilience4j.*}
 * lines read straight out of {@code application.properties}, plus this module's post-processor.
 * The HTTP client is a Mockito mock of {@link CatalogClient} — the post-processor only wraps that
 * type, so a mock of it is wrapped exactly as the real one is.
 *
 * <p>Reading the real file matters more than it looks. Almost every behaviour here is
 * CONFIGURATION: which exceptions count as failures, which are retried, how many calls open the
 * breaker. A test that built its own {@code CircuitBreakerConfig} would test Resilience4j, and
 * would keep passing after somebody deleted {@code record-exceptions} from the properties — which
 * would make every 404 count towards opening the breaker.
 */
@DisplayName("Resilience around catalog-service")
class ResilientCatalogTest {

    private static final ProductSnapshot PRODUCT =
            new ProductSnapshot(1L, "Keyboard", "Mechanical", new BigDecimal("49.99"), "peripherals", 10);

    private final CatalogClient http = mock(CatalogClient.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CircuitBreakerAutoConfiguration.class,
                    RetryAutoConfiguration.class,
                    BulkheadAutoConfiguration.class))
            .withUserConfiguration(CatalogResilienceConfig.class)
            .withBean(CatalogClient.class, () -> http)
            .withPropertyValues(shippedResilienceProperties());

    @Test
    @DisplayName("the HTTP client is replaced in place: the only CatalogGateway is the resilient one")
    void theClientIsWrapped() {
        context.run(ctx -> {
            assertThat(ctx).hasSingleBean(CatalogGateway.class);
            assertThat(ctx.getBean(CatalogGateway.class)).isInstanceOf(ResilientCatalog.class);
        });
    }

    @Test
    @DisplayName("the instances exist from startup, so the dashboard has series before anything fails")
    void theInstancesExistBeforeTheFirstCall() {
        context.run(ctx -> {
            assertThat(ctx.getBean(CircuitBreakerRegistry.class).find("catalog")).isPresent();
            assertThat(ctx.getBean(RetryRegistry.class).find("catalog")).isPresent();
            assertThat(ctx.getBean(BulkheadRegistry.class).find("catalog")).isPresent();
        });
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("an I/O failure is tried three times, then becomes a 503")
        void retriedThenUnavailable() {
            when(http.requireProduct(anyLong())).thenThrow(new ResourceAccessException("Connection refused"));

            context.run(ctx -> {
                assertThatThrownBy(() -> catalogue(ctx).requireProduct(1L))
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessageContaining("did not respond");

                verify(http, times(3)).requireProduct(1L);
            });
        }

        @Test
        @DisplayName("a blip recovers inside the retry: the caller never sees it")
        void aTransientFailureIsAbsorbed() {
            when(http.requireProduct(anyLong()))
                    .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY))
                    .thenReturn(PRODUCT);

            context.run(ctx -> {
                assertThat(catalogue(ctx).requireProduct(1L)).isEqualTo(PRODUCT);
                verify(http, times(2)).requireProduct(1L);
            });
        }

        @Test
        @DisplayName("a 404 is catalog-service working: not retried, not a failure, still a 404")
        void notFoundIsNotAFailure() {
            when(http.requireProduct(anyLong())).thenThrow(NotFoundException.product(99L));

            context.run(ctx -> {
                for (int i = 0; i < 10; i++) {
                    assertThatThrownBy(() -> catalogue(ctx).requireProduct(99L))
                            .isInstanceOf(NotFoundException.class);
                }

                verify(http, times(10)).requireProduct(99L);
                CircuitBreaker breaker = breaker(ctx);
                assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
                assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
            });
        }
    }

    @Nested
    @DisplayName("writes")
    class Writes {

        @Test
        @DisplayName("are never retried: a create that timed out may already have happened")
        void notRetried() {
            when(http.create(any())).thenThrow(new ResourceAccessException("Read timed out"));

            context.run(ctx -> {
                assertThatThrownBy(() -> catalogue(ctx).create(null))
                        .isInstanceOf(ServiceUnavailableException.class);

                verify(http, times(1)).create(any());
            });
        }

        @Test
        @DisplayName("a 4xx is the caller's problem, not an outage: passed through untouched")
        void clientErrorsPassThrough() {
            when(http.create(any())).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

            context.run(ctx -> {
                assertThatThrownBy(() -> catalogue(ctx).create(null))
                        .isInstanceOf(HttpClientErrorException.class);
                assertThat(breaker(ctx).getMetrics().getNumberOfFailedCalls()).isZero();
            });
        }
    }

    @Nested
    @DisplayName("the circuit breaker")
    class TheBreaker {

        @Test
        @DisplayName("opens after repeated failures, and then refuses at once without calling catalog-service")
        void opensAndFailsFast() {
            when(http.requireProduct(anyLong())).thenThrow(new ResourceAccessException("Connection refused"));

            context.run(ctx -> {
                CatalogGateway catalogue = catalogue(ctx);
                // Two reads = up to six attempts; the fifth failed attempt reaches the minimum
                // number of calls with a 100% failure rate, and the breaker opens.
                for (int i = 0; i < 2; i++) {
                    assertThatThrownBy(() -> catalogue.requireProduct(1L))
                            .isInstanceOf(ServiceUnavailableException.class);
                }
                assertThat(breaker(ctx).getState()).isEqualTo(CircuitBreaker.State.OPEN);
                clearInvocations(http);

                long started = System.nanoTime();
                assertThatThrownBy(() -> catalogue.requireProduct(1L))
                        .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                            assertThat(e).hasMessageContaining("temporarily unavailable");
                            // Retry-After is how long the breaker stays open.
                            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(10));
                        });
                Duration took = Duration.ofNanos(System.nanoTime() - started);

                verifyNoInteractions(http);
                // No network, no retry backoff: an open breaker costs next to nothing.
                assertThat(took).isLessThan(Duration.ofMillis(50));
            });
        }

        @Test
        @DisplayName("closes again once trial calls succeed in HALF_OPEN")
        void recovers() {
            when(http.requireProduct(anyLong())).thenReturn(PRODUCT);

            context.run(ctx -> {
                CircuitBreaker breaker = breaker(ctx);
                breaker.transitionToOpenState();
                breaker.transitionToHalfOpenState();

                for (int i = 0; i < 3; i++) {
                    assertThat(catalogue(ctx).requireProduct(1L)).isEqualTo(PRODUCT);
                }

                assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            });
        }
    }

    @Nested
    @DisplayName("the bulkhead")
    class TheBulkhead {

        @Test
        @DisplayName("refuses the 21st concurrent call at once, and that refusal does not count against catalog-service")
        void refusesTheExcess() throws Exception {
            CountDownLatch release = new CountDownLatch(1);
            when(http.requireProduct(anyLong())).thenAnswer(invocation -> {
                release.await(5, TimeUnit.SECONDS);
                return PRODUCT;
            });

            context.run(ctx -> {
                CatalogGateway catalogue = catalogue(ctx);
                var bulkhead = ctx.getBean(BulkheadRegistry.class).bulkhead("catalog");
                ExecutorService pool = Executors.newFixedThreadPool(20);
                try {
                    List<Future<ProductSnapshot>> slow = new ArrayList<>();
                    for (int i = 0; i < 20; i++) {
                        slow.add(pool.submit(() -> catalogue.requireProduct(1L)));
                    }
                    waitUntil(() -> bulkhead.getMetrics().getAvailableConcurrentCalls() == 0);

                    long started = System.nanoTime();
                    assertThatThrownBy(() -> catalogue.requireProduct(1L))
                            .isInstanceOf(ServiceUnavailableException.class)
                            .hasMessageContaining("busy");
                    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(50));

                    release.countDown();
                    for (Future<ProductSnapshot> call : slow) {
                        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo(PRODUCT);
                    }
                    verify(http, times(20)).requireProduct(1L);
                    // Our own saturation is not catalog-service failing: the breaker saw 20
                    // successes and nothing else.
                    assertThat(breaker(ctx).getMetrics().getNumberOfFailedCalls()).isZero();
                    assertThat(breaker(ctx).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
                } finally {
                    release.countDown();
                    pool.shutdownNow();
                }
            });
        }
    }

    private static CatalogGateway catalogue(AssertableApplicationContext ctx) {
        return ctx.getBean(CatalogGateway.class);
    }

    private static CircuitBreaker breaker(AssertableApplicationContext ctx) {
        return ctx.getBean(CircuitBreakerRegistry.class).circuitBreaker("catalog");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 5 s");
            }
            Thread.sleep(5);
        }
    }

    /** Every {@code resilience4j.*} line of the application's own properties file, as-is. */
    static String[] shippedResilienceProperties() {
        Properties file = new Properties();
        try (InputStream in = ResilientCatalogTest.class.getResourceAsStream("/application.properties")) {
            file.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String[] lines = file.stringPropertyNames().stream()
                .filter(key -> key.startsWith("resilience4j."))
                .map(key -> key + "=" + file.getProperty(key))
                .toArray(String[]::new);
        if (lines.length == 0) {
            throw new IllegalStateException("no resilience4j.* properties found - this test would test nothing");
        }
        return lines;
    }
}
