package com.ecomdemo.inventory;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and writes stock rows. Internal: everything outside goes through InventoryService. */
interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

    /**
     * Stock for many products in one query.
     *
     * <p>Exists because the catalogue listing needs a quantity per product and asking one at a
     * time would be the N+1 problem with extra steps — and, once this is an HTTP call between two
     * services, N+1 network round trips rather than N+1 queries. Building the batch endpoint now,
     * while it is still a method call, means the caller is already shaped for it.
     */
    List<ProductStock> findAllByProductIdIn(Collection<Long> productIds);

    /**
     * The stock rows for an order's products, LOCKED until the transaction ends ({@code SELECT ...
     * FOR UPDATE}). Phase 24, for the saga's reservation.
     *
     * <p>Why a lock here when the rest of this service relies on {@code @Version}: the saga's
     * reservation runs on a Kafka listener, and a lost optimistic race there is not a 409 a
     * shopper retries - it is a blocking retry of the whole message (three attempts, then the
     * dead-letter topic, leaving the order PENDING). A busy product would make that likely.
     * Waiting for the row is cheaper and certain.
     *
     * <p>ORDER BY product id is the deadlock guard: two orders that share products always lock
     * them in the same sequence, so neither can hold one row while waiting for the other's.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductStock s WHERE s.productId IN :ids ORDER BY s.productId")
    List<ProductStock> lockAllByProductIdIn(@Param("ids") Collection<Long> productIds);
}
