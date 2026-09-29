package com.ecomdemo.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

interface ClosedOrderRepository extends JpaRepository<ClosedOrder, Long> {
}
