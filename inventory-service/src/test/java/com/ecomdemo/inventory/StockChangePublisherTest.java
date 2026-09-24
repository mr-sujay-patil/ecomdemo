package com.ecomdemo.inventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * When the stock-changed message is sent — and, more importantly, when it is not.
 *
 * <h2>This assertion MOVED here in Phase 20b</h2>
 *
 * <p>{@code CacheApiIT} in the monolith owned it. It warmed a cache, changed stock inside a
 * transaction, rolled the transaction back, and proved the cache had not been evicted — because
 * the evictor was an {@code @TransactionalEventListener} and refused to run before a commit.
 *
 * <p>That listener is a Kafka consumer in another application now, and it cannot see this
 * transaction at all. <strong>The guarantee did not weaken; it moved to the other end.</strong> It
 * is enforced here, at the publisher, which holds the send until its own transaction commits — so
 * a reservation that rolls back still causes no eviction anywhere.
 *
 * <p>A guarantee that moves between services needs its test to move with it, or it becomes a
 * guarantee nobody checks. That is the whole reason this file exists.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({InventoryService.class, StockChangePublisher.class})
/*
 * NOT_SUPPORTED, and without it this whole file is a lie.
 *
 * @DataJpaTest wraps every test in a transaction it ROLLS BACK afterwards, which is usually a
 * convenience - no cleanup between tests. Here it is fatal: the TransactionTemplate below would
 * join that outer transaction instead of creating its own, so nothing would ever commit, and an
 * AFTER_COMMIT listener that never fires makes BOTH tests pass for the wrong reason. The
 * rollback case would be green because nothing was published; the commit case would fail, which
 * is the only reason the problem was noticed at all.
 *
 * Suspending the test transaction means TransactionTemplate opens a real one that really commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("StockChangePublisher")
class StockChangePublisherTest {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private ProductStockRepository stock;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private KafkaTemplate<String, Object> kafka;

    @org.junit.jupiter.api.AfterEach
    void clearStock() {
        // The test transaction no longer cleans up, because there is no test transaction.
        stock.deleteAll();
    }

    @Test
    @DisplayName("a committed stock change IS published")
    void aCommittedChangeIsPublished() {
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        stock.save(new ProductStock(1L, 9));

        transactions.executeWithoutResult(status -> inventory.reserve(1L, "Lamp", 2));

        // Keyed by product id, so every change to one product lands in one partition and is read
        // in the order it was written.
        verify(kafka).send(eq(StockChangePublisher.TOPIC), eq("1"), any());
    }

    @Test
    @DisplayName("a ROLLED BACK stock change is never published, so nothing is evicted for it")
    void aRolledBackChangeIsNotPublished() {
        stock.save(new ProductStock(1L, 9));

        transactions.executeWithoutResult(status -> {
            inventory.reserve(1L, "Lamp", 2);
            status.setRollbackOnly();
        });

        // A message cannot be un-sent. Announcing a reduction that then rolled back would evict a
        // cache entry that was never stale and invite a reader to repopulate it from a row that
        // does not exist - which is the hazard AFTER_COMMIT has guarded against since Phase 16.
        verify(kafka, never()).send(anyString(), anyString(), any());
    }
}
