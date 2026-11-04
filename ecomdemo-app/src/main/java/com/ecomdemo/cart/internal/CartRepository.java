package com.ecomdemo.cart.internal;

import com.ecomdemo.cart.Cart;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
            + "where c.userId = :userId")
    Optional<Cart> findByUserId(@Param("userId") Long userId);

    /**
     * Gives the account a cart unless it has one, and never fails because another request did it
     * first (KI-081).
     *
     * <p>A cart is created on the first request that needs it, and {@code uq_cart_user} allows
     * one per account. Find-then-{@code save()} is two steps, so two first requests arriving
     * together both found nothing, both inserted, and the second insert's constraint violation
     * was a 500. Catching that exception would not help: PostgreSQL aborts the whole transaction
     * on a failed statement, so the request could not go on to read the winner's cart.
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes the check and the insert one statement. When
     * another transaction has just inserted the same account's row, this one waits for it to
     * commit and then does nothing, with no error, and the next query sees that row. The target
     * {@code (user_id)} names {@code uq_cart_user}, so any other conflict still fails loudly.
     *
     * <p>PostgreSQL SQL, like the outbox's advisory lock: H2 does not parse it, so it is tested
     * against PostgreSQL by {@code CartApiIT} (every test there creates a cart through it), and
     * the unit tests mock this repository.
     *
     * @return 1 if this call created the cart, 0 if the account already had one
     */
    @Modifying
    @Query(value = "insert into cart (user_id) values (:userId) on conflict (user_id) do nothing",
            nativeQuery = true)
    int createIfAbsent(@Param("userId") Long userId);
}
