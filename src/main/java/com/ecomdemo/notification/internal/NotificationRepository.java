package com.ecomdemo.notification.internal;

import com.ecomdemo.notification.Notification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByOrderId(Long orderId);

    long countByOrderId(Long orderId);
}
