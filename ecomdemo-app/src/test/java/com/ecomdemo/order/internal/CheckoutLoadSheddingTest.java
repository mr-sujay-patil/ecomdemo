package com.ecomdemo.order.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ecomdemo.jwt.CurrentUser;
import com.ecomdemo.metrics.CheckoutMetrics;
import com.ecomdemo.metrics.MetricNames;
import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.shared.ServiceUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Checkout load shedding (KI-005), against the SHIPPED bulkhead configuration.
 *
 * <p>The bulkhead's size is configuration, so the limit is read from {@code application.properties}
 * itself and the test fills exactly that many checkouts: a later edit to the number cannot leave
 * this proving something that is no longer true. The checkouts are held open inside
 * {@code placeOnce()} on a latch - the same place a real one waits for a database connection - and
 * no database is involved, because what is under test is the DOOR, not the order.
 */
@DisplayName("Checkout load shedding")
class CheckoutLoadSheddingTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BulkheadAutoConfiguration.class))
            .withPropertyValues(shippedCheckoutBulkhead());

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OrderPlacementService placement = mock(OrderPlacementService.class);

    @Test
    @DisplayName("the ninth concurrent checkout is refused at once with a 503 and Retry-After, not queued")
    void excessIsShedImmediately() throws Exception {
        context.run(ctx -> {
            int limit = shippedLimit();
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch inside = new CountDownLatch(limit);
            when(placement.placeOnce()).thenAnswer(call -> {
                inside.countDown();
                release.await(10, TimeUnit.SECONDS);
                return response();
            });
            OrderService service = service(ctx);
            ExecutorService pool = Executors.newFixedThreadPool(limit);
            try {
                List<Future<OrderResponse>> held = new ArrayList<>();
                for (int i = 0; i < limit; i++) {
                    held.add(pool.submit(service::place));
                }
                assertThat(inside.await(5, TimeUnit.SECONDS)).as("%d checkouts in progress", limit).isTrue();

                long started = System.nanoTime();
                assertThatThrownBy(service::place)
                        .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                            assertThat(e).hasMessageContaining("busy");
                            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(1));
                        });
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .as("a shed request must fail fast, not wait for a connection")
                        .isLessThan(Duration.ofMillis(200));

                release.countDown();
                for (Future<OrderResponse> checkout : held) {
                    assertThat(checkout.get(5, TimeUnit.SECONDS)).isNotNull();
                }
                assertThat(shedCount()).isEqualTo(1);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("a shed request touches nothing: no placement, no audit row")
    void aShedRequestDoesNoWork() throws Exception {
        context.run(ctx -> {
            int limit = shippedLimit();
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch inside = new CountDownLatch(limit);
            when(placement.placeOnce()).thenAnswer(call -> {
                inside.countDown();
                release.await(10, TimeUnit.SECONDS);
                return response();
            });
            OrderService service = service(ctx);
            ExecutorService pool = Executors.newFixedThreadPool(limit);
            try {
                for (int i = 0; i < limit; i++) {
                    pool.submit(service::place);
                }
                inside.await(5, TimeUnit.SECONDS);
                org.mockito.Mockito.clearInvocations(placement, audit);

                assertThatThrownBy(service::place).isInstanceOf(ServiceUnavailableException.class);

                org.mockito.Mockito.verifyNoInteractions(placement, audit);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("permits come back: after the checkouts finish, or fail, the door opens again")
    void permitsAreReleased() {
        context.run(ctx -> {
            when(placement.placeOnce())
                    .thenThrow(new IllegalStateException("boom"))
                    .thenReturn(response());
            OrderService service = service(ctx);
            int limit = shippedLimit();

            // More sequential checkouts than the limit, half of them failing: a leaked permit on
            // either path would run the bulkhead dry and start shedding.
            for (int i = 0; i < limit * 3; i++) {
                try {
                    service.place();
                } catch (IllegalStateException expected) {
                    // the first call; the rest succeed
                }
            }

            assertThat(shedCount()).isZero();
        });
    }

    private final OrderAuditService audit = mock(OrderAuditService.class);

    private OrderService service(AssertableApplicationContext ctx) {
        return new OrderService(
                mock(OrderRepository.class),
                placement,
                audit,
                mock(CurrentUser.class),
                new CheckoutMetrics(meters),
                ctx.getBean(BulkheadRegistry.class));
    }

    private long shedCount() {
        return meters.get(MetricNames.CHECKOUT_DURATION)
                .tag(MetricNames.TAG_OUTCOME, "shed")
                .timer()
                .count();
    }

    private static OrderResponse response() {
        return new OrderResponse(
                1L, Instant.now(), "asha", OrderStatus.PENDING, null, Instant.now(), BigDecimal.TEN, List.of());
    }

    private static int shippedLimit() {
        return Integer.parseInt(
                applicationProperties().getProperty("resilience4j.bulkhead.instances.checkout.max-concurrent-calls"));
    }

    private static String[] shippedCheckoutBulkhead() {
        Properties file = applicationProperties();
        return file.stringPropertyNames().stream()
                .filter(name -> name.startsWith("resilience4j.bulkhead.instances.checkout."))
                .map(name -> name + "=" + file.getProperty(name))
                .toArray(String[]::new);
    }

    private static Properties applicationProperties() {
        Properties file = new Properties();
        try (InputStream in = CheckoutLoadSheddingTest.class.getResourceAsStream("/application.properties")) {
            file.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return file;
    }
}
