package com.ecomdemo.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Two threads racing for the last unit, against a real database.
 *
 * <h2>This coverage MOVED here in Phase 20b; it was not written from scratch</h2>
 *
 * <p>{@code ConcurrentCheckoutTest} in the monolith asserted exactly this: two checkouts for the
 * last unit leave one order and zero stock, because the second {@code UPDATE} matches no row and
 * Hibernate raises an optimistic-locking failure. When inventory became a service, the app-side
 * test could no longer create the race — it now reaches stock over HTTP, and what it was really
 * testing was a database lock two processes away.
 *
 * <p>So the assertion follows the behaviour. It is a better home for it: the race is between two
 * transactions on {@code product_stock}, and this is the only module where both exist.
 *
 * <h2>Why a container and not H2</h2>
 *
 * <p>The claim is about what the database does when two transactions update the same row with a
 * version predicate. H2 in PostgreSQL-compatibility mode is an imitation of that, and a test that
 * passes against the imitation proves the imitation — the same judgement Phase 7 made when it put
 * the original checkout race on a real PostgreSQL.
 */
@SpringBootTest
@Import(ConcurrentReservationTest.Containers.class)
@DisplayName("Concurrent reservation")
class ConcurrentReservationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"));
        }
    }

    /** No broker in this test; what it asserts happens before any message would be sent. */
    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private ProductStockRepository stock;

    @Test
    @DisplayName("two threads taking the last unit leave exactly one winner and zero stock")
    void twoThreadsTakingTheLastUnit() throws Exception {
        Long productId = 9001L;
        stock.save(new ProductStock(productId, 1));

        // Both threads are released at the same instant, because a race that is not actually
        // simultaneous is just two sequential calls and would pass with no locking at all.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        ExecutorService threads = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            threads.submit(() -> {
                try {
                    start.await();
                    inventory.reserve(productId, "Last One", 1);
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    // Either the optimistic lock fired, or the loser read a row that already said
                    // zero. Both are the system refusing to oversell, which is the claim.
                    failed.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });
        }

        start.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        threads.shutdownNow();

        assertThat(succeeded.get()).as("exactly one thread may take the last unit").isEqualTo(1);
        assertThat(failed.get()).as("and the other must be refused").isEqualTo(1);
        assertThat(inventory.quantityFor(productId))
                .as("stock cannot go negative, and cannot be left at one")
                .isZero();
    }
}
