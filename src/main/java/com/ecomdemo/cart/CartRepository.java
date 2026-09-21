package com.ecomdemo.cart;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Loads the one cart with its items and their products in a single query.
     *
     * <p>The associations are declared LAZY, so without this JOIN FETCH the services would
     * either hit a LazyInitializationException (the session closes when the repository call
     * returns) or fire one query per line. {@code left join} keeps an empty cart findable, and
     * {@code distinct} collapses the duplicate cart rows a collection join produces.
     */
    @Query("select distinct c from Cart c left join fetch c.items i left join fetch i.product")
    Optional<Cart> findCart();
}
