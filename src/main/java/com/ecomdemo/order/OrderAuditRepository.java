package com.ecomdemo.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderAuditRepository extends JpaRepository<OrderAudit, Long> {

    /** Newest first, which is how an audit trail is always read. */
    List<OrderAudit> findAllByOrderByRecordedAtDescIdDesc();

    List<OrderAudit> findByOutcomeOrderByIdDesc(OrderOutcome outcome);
}
