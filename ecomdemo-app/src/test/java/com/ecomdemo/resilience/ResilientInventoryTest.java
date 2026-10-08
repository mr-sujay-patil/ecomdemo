package com.ecomdemo.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecomdemo.clients.inventory.InventoryClient;
import com.ecomdemo.clients.inventory.InventoryGateway;
import com.ecomdemo.shared.InsufficientStockException;
import com.ecomdemo.shared.ServiceUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * The resilience policy around inventory-service (KI-004), run against the SHIPPED configuration.
 *
 * <p>Built like {@link ResilientCatalogTest}, and for the same reason: nearly every behaviour is
 * configuration (what counts as a failure, what is retried, when the breaker opens), so the
 * {@code resilience4j.*} lines come straight out of {@code application.properties}. The HTTP client
 * is a Mockito mock of {@link InventoryClient}, which the post-processor wraps exactly as it wraps
 * the real one.
 */
@DisplayName("Resilience around inventory-service")
class ResilientInventoryTest {

    private final InventoryClient http = mock(InventoryClient.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CircuitBreakerAutoConfiguration.class,
                    RetryAutoConfiguration.class,
                    BulkheadAutoConfiguration.class))
            .withUserConfiguration(InventoryResilienceConfig.class)
            .withBean(InventoryClient.class, () -> http)
            .withPropertyValues(ResilientCatalogTest.shippedResilienceProperties());

    @Test
    @DisplayName("the HTTP client is replaced in place: the only InventoryGateway is the resilient one")
    void theClientIsWrapped() {
        context.run(ctx -> {
            assertThat(ctx).hasSingleBean(InventoryGateway.class);
            assertThat(ctx.getBean(InventoryGateway.class)).isInstanceOf(ResilientInventory.class);
        });
    }

    @Test
    @DisplayName("the instances exist from startup, so the dashboard has series before anything fails")
    void theInstancesExistBeforeTheFirstCall() {
        context.run(ctx -> {
            assertThat(ctx.getBean(CircuitBreakerRegistry.class).find("inventory")).isPresent();
            assertThat(ctx.getBean(RetryRegistry.class).find("inventory")).isPresent();
            assertThat(ctx.getBean(BulkheadRegistry.class).find("inventory")).isPresent();
        });
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("an I/O failure is tried twice, then becomes a 503")
        void retriedThenUnavailable() {
            when(http.quantityFor(anyLong())).thenThrow(new ResourceAccessException("Read timed out"));

            context.run(ctx -> {
                assertThatThrownBy(() -> inventory(ctx).quantityFor(1L))
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessageContaining("did not respond");

                verify(http, times(2)).quantityFor(1L);
            });
        }

        @Test
        @DisplayName("a blip recovers inside the retry: the caller never sees it")
        void aTransientFailureIsAbsorbed() {
            when(http.quantityFor(anyLong()))
                    .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY))
                    .thenReturn(7);

            context.run(ctx -> assertThat(inventory(ctx).quantityFor(1L)).isEqualTo(7));
        }

        @Test
        @DisplayName("'not enough stock' is inventory-service working: not retried, not a failure, still a 409")
        void insufficientStockIsNotAFailure() {
            doThrow(new InsufficientStockException("Keyboard", 5, 2))
                    .when(http)
                    .requireAvailable(anyLong(), anyString(), anyInt());

            context.run(ctx -> {
                assertThatThrownBy(() -> inventory(ctx).requireAvailable(1L, "Keyboard", 5))
                        .isInstanceOf(InsufficientStockException.class);

                verify(http, times(1)).requireAvailable(1L, "Keyboard", 5);
                assertThat(breaker(ctx).getMetrics().getNumberOfFailedCalls()).isZero();
            });
        }
    }

    @Nested
    @DisplayName("writes")
    class Writes {

        @Test
        @DisplayName("setStockLevel and forget are never retried: a write that timed out may already have happened")
        void otherWritesAreNotRetried() {
            doThrow(new ResourceAccessException("Read timed out")).when(http).setStockLevel(anyLong(), anyInt());
            doThrow(new ResourceAccessException("Read timed out")).when(http).forget(anyLong());

            context.run(ctx -> {
                assertThatThrownBy(() -> inventory(ctx).setStockLevel(1L, 5))
                        .isInstanceOf(ServiceUnavailableException.class);
                assertThatThrownBy(() -> inventory(ctx).forget(1L)).isInstanceOf(ServiceUnavailableException.class);

                verify(http, times(1)).setStockLevel(1L, 5);
                verify(http, times(1)).forget(1L);
            });
        }

    }

    @Nested
    @DisplayName("the circuit breaker")
    class TheBreaker {

        @Test
        @DisplayName("opens after repeated failures, then refuses at once without calling inventory-service")
        void opensAndFailsFast() {
            when(http.quantityFor(anyLong())).thenThrow(new ResourceAccessException("Connection refused"));

            context.run(ctx -> {
                openTheBreaker(ctx);
                assertThat(breaker(ctx).getState()).isEqualTo(CircuitBreaker.State.OPEN);
                clearInvocations(http);

                long started = System.nanoTime();
                assertThatThrownBy(() -> inventory(ctx).quantityFor(1L))
                        .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                            assertThat(e).hasMessageContaining("temporarily unavailable");
                            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(10));
                        });

                verifyNoInteractions(http);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(50));
            });
        }

        @Test
        @DisplayName("a write is refused at once while it is open: the caller fails fast, it does not hang")
        void writesAreRefusedWhileOpen() {
            when(http.quantityFor(anyLong())).thenThrow(new ResourceAccessException("Connection refused"));

            context.run(ctx -> {
                openTheBreaker(ctx);
                clearInvocations(http);

                assertThatThrownBy(() -> inventory(ctx).setStockLevel(1L, 5))
                        .isInstanceOf(ServiceUnavailableException.class);

                verifyNoInteractions(http);
            });
        }
    }

    @Test
    @DisplayName("timeouts compose: the worst case a shopper waits on a read fits the 2 s budget")
    void theWorstCaseFitsTheBudget() {
        // attempts x per-attempt read timeout + every backoff at its jittered maximum, computed
        // from the SHIPPED properties so that changing one without redoing the sum fails here.
        Properties file = ResilientCatalogTest.applicationProperties();
        int attempts = Integer.parseInt(file.getProperty("resilience4j.retry.instances.inventory.max-attempts"));
        Duration readTimeout = duration(file.getProperty("ecomdemo.inventory.read-timeout"));
        Duration firstWait = duration(file.getProperty("resilience4j.retry.instances.inventory.wait-duration"));
        double multiplier = Double.parseDouble(
                file.getProperty("resilience4j.retry.instances.inventory.exponential-backoff-multiplier", "1"));
        double jitter = Double.parseDouble(
                file.getProperty("resilience4j.retry.instances.inventory.randomized-wait-factor", "0"));

        Duration worst = readTimeout.multipliedBy(attempts);
        for (int retry = 0; retry < attempts - 1; retry++) {
            worst = worst.plusMillis((long) (firstWait.toMillis() * Math.pow(multiplier, retry) * (1 + jitter)));
        }

        assertThat(worst).isLessThan(Duration.ofSeconds(2));
    }

    /** Three failed reads are six attempts, enough to reach the minimum number of calls. */
    private static void openTheBreaker(AssertableApplicationContext ctx) {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> inventory(ctx).quantityFor(1L)).isInstanceOf(ServiceUnavailableException.class);
        }
    }

    private static Duration duration(String value) {
        return org.springframework.boot.convert.DurationStyle.detectAndParse(value);
    }

    private static InventoryGateway inventory(AssertableApplicationContext ctx) {
        return ctx.getBean(InventoryGateway.class);
    }

    private static CircuitBreaker breaker(AssertableApplicationContext ctx) {
        return ctx.getBean(CircuitBreakerRegistry.class).circuitBreaker("inventory");
    }
}
