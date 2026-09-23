package com.ecomdemo.inventory;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
