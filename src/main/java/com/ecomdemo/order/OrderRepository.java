package com.ecomdemo.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Loads orders with their lines in one query instead of one query per order (N+1). */
    @Query("select distinct o from Order o left join fetch o.items order by o.id")
    List<Order> findAllWithItems();

    @Query("select distinct o from Order o left join fetch o.items where o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);
}
