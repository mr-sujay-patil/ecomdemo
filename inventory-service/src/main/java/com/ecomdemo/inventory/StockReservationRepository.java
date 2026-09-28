package com.ecomdemo.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** An order's reservations. Internal: InventoryService is the only writer. */
interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findByOrderId(Long orderId);

    List<StockReservation> findByOrderIdAndStatus(Long orderId, StockReservation.Status status);
}
