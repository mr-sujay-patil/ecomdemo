package com.ecomdemo.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.messaging.ProcessedEvent;
import com.ecomdemo.messaging.ProcessedEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The idempotency decision, without a broker.
 *
 * <p>Whether a redelivery really happens, and what a rebalance does, are claims about Kafka and
 * are made in {@code OrderPlacedKafkaIT} against a real one. What is worth pinning down here is
 * the rule itself: an event already recorded does no work, an unseen one does, and the marker is
 * written with the id that came in the message rather than one invented locally — which is the
 * detail that makes the whole scheme work and the easiest one to break in a refactor.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService")
class NotificationServiceTest {

    @Mock
    private NotificationRepository notifications;

    @Mock
    private ProcessedEventRepository processedEvents;

    @InjectMocks
    private NotificationService notificationService;

    @Captor
    private ArgumentCaptor<ProcessedEvent> processedEventCaptor;

    @Captor
    private ArgumentCaptor<Notification> notificationCaptor;

    private static OrderPlacedEvent event(UUID eventId, long orderId) {
        return new OrderPlacedEvent(
                eventId, orderId, "alice", new BigDecimal("2499.50"), 3, Instant.now());
    }

    @Test
    @DisplayName("writes a notification for an event it has not seen")
    void writesANotification() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);

        boolean handled = notificationService.handle(event(eventId, 42L));

        assertThat(handled).isTrue();
        verify(notifications).save(notificationCaptor.capture());
        Notification written = notificationCaptor.getValue();
        assertThat(written.getOrderId()).isEqualTo(42L);
        assertThat(written.getRecipient()).isEqualTo("alice");
        assertThat(written.getMessage()).contains("42").contains("2499.50").contains("3 item");
    }

    @Test
    @DisplayName("remembers the event id FROM THE MESSAGE, not one of its own")
    void remembersTheEventIdFromTheMessage() {
        // The single most breakable line in the design. An id generated here — or a database
        // sequence — would be different on every delivery and would recognise no duplicate at
        // all, while every test that only checked "a marker was saved" would still pass.
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);

        notificationService.handle(event(eventId, 42L));

        verify(processedEvents).save(processedEventCaptor.capture());
        assertThat(processedEventCaptor.getValue().getEventId()).isEqualTo(eventId);
        assertThat(processedEventCaptor.getValue().getEventType()).isEqualTo("OrderPlacedEvent");
    }

    @Test
    @DisplayName("does nothing at all for an event it has already handled")
    void ignoresADuplicate() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(true);

        boolean handled = notificationService.handle(event(eventId, 42L));

        assertThat(handled).isFalse();
        verify(notifications, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("marks the event processed BEFORE writing the notification")
    void marksTheEventBeforeDoingTheWork() {
        // Ordering matters because the marker's primary key is the event id: two consumers racing
        // on the same record both pass the existsById check, and the one that inserts second is
        // refused by the database and rolls back. If the notification were written first, the
        // loser would have written it before being refused — and the unique constraint on
        // order_id would be the only thing left standing between the design and two notifications.
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);

        notificationService.handle(event(eventId, 42L));

        var inOrder = org.mockito.Mockito.inOrder(processedEvents, notifications);
        inOrder.verify(processedEvents).save(any());
        inOrder.verify(notifications).save(any());
    }
}
