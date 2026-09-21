package com.ecomdemo.cart;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Loads one account's cart with its items and their products in a single query.
     *
     * <p>The associations are declared LAZY, so without this JOIN FETCH the services would
     * either hit a LazyInitializationException (the session closes when the repository call
     * returns) or fire one query per line. {@code left join} keeps an empty cart findable, and
     * {@code distinct} collapses the duplicate cart rows a collection join produces.
     *
     * <p>The {@code where} clause is the tenancy rule, and it is written here — in the query —
     * rather than as a filter applied to the result. A query that loads every cart and then
     * discards the ones belonging to other people has already read data the caller may not see,
     * and is one forgotten {@code filter} away from returning it. Making the owner part of the
     * SQL means the rows never leave the database in the first place.
     */
    @Query("select distinct c from Cart c left join fetch c.items i left join fetch i.product "
            + "where c.user.id = :userId")
    Optional<Cart> findByUserId(@Param("userId") Long userId);
}
