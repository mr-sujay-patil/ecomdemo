package com.ecomdemo.notification.internal;

import com.ecomdemo.notification.Notification;
import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.messaging.EventDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an {@code OrderPlacedEvent} into a notification, exactly once.
 *
 * <p><strong>Why "exactly once" is a property of this class and not of Kafka.</strong> Kafka
 * delivers <em>at least once</em>, and no setting changes that for work done outside the broker.
 * A consumer that writes its row and dies before committing its offset will be handed the same
 * record again; so will every consumer in the group after a rebalance. The broker cannot know
 * whether the work happened, because the work happened in PostgreSQL.
 *
 * <p>So the consumer recognises what it has already done. The check and the work happen in
 * <strong>one transaction</strong>: if the notification is written and the marker is not, a
 * redelivery would write a second notification; if the marker is written and the notification is
 * not, the order is never notified and nothing retries. Either half alone is worse than neither,
 * which is why they cannot be two transactions.
 *
 * <p>The marker is inserted <em>first</em>, deliberately. Its primary key is the event id, so two
 * consumers racing on the same record do not both pass a {@code SELECT} and proceed — the second
 * insert violates the key and that transaction rolls back, leaving exactly one notification. The
 * check is the cheap path for the common case; the constraint is what makes it correct.
 *
 * <p>Since Phase 19 that mechanism lives behind {@code EventDeduplicator} in the messaging module,
 * which is where it belongs: this class knows that an event may arrive twice and that it must act
 * once, and no longer knows which table records the fact or in what order the two writes happen.
 * The second consumer this application grows inherits the rule instead of copying it.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final String EVENT_TYPE = OrderPlacedEvent.class.getSimpleName();

    private final NotificationRepository notifications;
    private final EventDeduplicator deduplicator;

    public NotificationService(
            NotificationRepository notifications, EventDeduplicator deduplicator) {
        this.notifications = notifications;
        this.deduplicator = deduplicator;
    }

    /**
     * @return true if a notification was written, false if this event had already been handled
     */
    @Transactional
    public boolean handle(OrderPlacedEvent event) {
        if (!deduplicator.claim(event.eventId(), EVENT_TYPE)) {
            // Not a warning and not an error: a redelivery is Kafka working as designed, and a
            // log level that says otherwise trains people to ignore it. It is logged at all
            // because a SUDDEN RISE in duplicates means something else — a consumer failing to
            // commit offsets, or a rebalance loop — and that is worth being able to see.
            log.info(
                    "Ignoring OrderPlacedEvent {} for order {}: already processed",
                    event.eventId(),
                    event.orderId());
            return false;
        }

        Notification notification =
                new Notification(
                        event.orderId(),
                        event.username(),
                        "Thank you! Order %d for %s (%d item(s)) has been placed."
                                .formatted(event.orderId(), event.totalAmount(), event.itemCount()));
        notifications.save(notification);

        // The phase asks for "a log line and a notifications row", and this is the log line. It
        // carries the correlation id of the request that placed the order only if one is in the
        // MDC — and it is not: this runs on a Kafka listener thread, long after that HTTP request
        // finished. Carrying it across the boundary means putting it in the MESSAGE, which is a
        // real and worthwhile change and is exactly what Phase 23's tracing does properly.
        log.info(
                "Notification queued for order {} to {}",
                event.orderId(),
                event.username());
        return true;
    }
}
