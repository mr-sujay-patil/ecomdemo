package com.ecomdemo.cart.internal;

import com.ecomdemo.cart.Cart;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Loads one account's cart with its items and their products in a single query.
     *
     * <p>The items are declared LAZY, so without this JOIN FETCH the services would either hit a
     * LazyInitializationException (the session closes when the repository call returns) or fire
     * one query per line. {@code left join} keeps an empty cart findable, and {@code distinct}
     * collapses the duplicate cart rows a collection join produces.
     *
     * <p><strong>Since Phase 20 there is one fetch here, not two.</strong> It used to also fetch
     * {@code i.product}, because each line held an association to the catalogue and rendering a
     * cart meant reading every product. A line now remembers the name and price it was added at,
     * so that second join has nothing to fetch — and the N+1 it existed to prevent cannot happen,
     * because there is no per-line lookup left to make. Removing a foreign key removed a query.
     *
     * <p>The {@code where} clause is the tenancy rule, and it is written here — in the query —
     * rather than as a filter applied to the result. A query that loads every cart and then
     * discards the ones belonging to other people has already read data the caller may not see,
     * and is one forgotten {@code filter} away from returning it. Making the owner part of the
     * SQL means the rows never leave the database in the first place.
     */
    @Query("select distinct c from Cart c left join fetch c.items i "
            + "where c.user.id = :userId")
    Optional<Cart> findByUserId(@Param("userId") Long userId);
}
