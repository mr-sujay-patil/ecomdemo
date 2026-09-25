package com.ecomdemo.order.internal;

import com.ecomdemo.order.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
