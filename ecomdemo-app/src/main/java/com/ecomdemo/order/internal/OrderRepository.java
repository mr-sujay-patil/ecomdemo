package com.ecomdemo.order.internal;

import com.ecomdemo.order.Order;
import com.ecomdemo.order.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * One account's orders, with their lines, in one query instead of one query per order (N+1).
     *
     * <p>There is no longer a "load every order" query, and that is the point. The only way to
     * read the order table from the application is through a method that requires a user id, so
     * "show me my orders" and "show me all orders" cannot be confused by a caller, by a future
     * endpoint, or by a mistyped argument. An administrative report over all orders would be a
     * new, deliberately named method behind its own authorization rule.
     */
    @Query("select distinct o from Order o left join fetch o.items where o.userId = :userId order by o.id")
    List<Order> findAllByUserIdWithItems(@Param("userId") Long userId);

    /**
     * One order by id, whoever placed it.
     *
     * <p>Deliberately not filtered by user: the ownership check happens in {@code OrderService}
     * with {@code @PostAuthorize}, so that asking for somebody else's order is answered 403
     * ("that is not yours") rather than 404 ("no such thing"). Both are defensible — see the
     * discussion on {@code OrderService.findById} — and this is the query that makes the first
     * one possible.
     */
    @Query("select distinct o from Order o left join fetch o.items where o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);

    /**
     * Moves an order out of PENDING, if - and only if - it is still PENDING. Phase 24's semantic
     * lock, as one statement.
     *
     * <p>A conditional UPDATE rather than load, check, save. The check and the write are one
     * atomic step in the database, so no interleaving of two replies can let both through, and a
     * late or duplicate reply for an order that has already been decided changes nothing and
     * reports zero rows. The caller acts on that number: only the reply that actually moved the
     * order goes on to publish anything.
     *
     * <p>{@code clearAutomatically} because the persistence context may hold this order from
     * before the update; without it a later read in the same transaction would see PENDING.
     *
     * @return 1 if this call moved the order, 0 if it was already decided (or does not exist)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Order o
               set o.status = :to, o.statusReason = :reason, o.statusChangedAt = :at
             where o.id = :id and o.status = com.ecomdemo.order.OrderStatus.PENDING
            """)
    int transition(
            @Param("id") Long id,
            @Param("to") OrderStatus to,
            @Param("reason") String reason,
            @Param("at") Instant at);

    /**
     * The oldest PENDING orders placed before {@code cutoff}: the saga deadline's work list
     * (Phase 32). Ids only - the reconciler loads each order when it gets to it, so a long list does
     * not hold a batch of entities that other transactions are changing.
     */
    @Query("""
            select o.id from Order o
             where o.status = com.ecomdemo.order.OrderStatus.PENDING and o.placedAt < :cutoff
             order by o.placedAt
            """)
    List<Long> findPendingPlacedBefore(@Param("cutoff") Instant cutoff, Pageable page);

    /** How many PENDING orders were placed before {@code cutoff}: the overdue gauge. */
    @Query("""
            select count(o) from Order o
             where o.status = com.ecomdemo.order.OrderStatus.PENDING and o.placedAt < :cutoff
            """)
    long countPendingPlacedBefore(@Param("cutoff") Instant cutoff);
}
