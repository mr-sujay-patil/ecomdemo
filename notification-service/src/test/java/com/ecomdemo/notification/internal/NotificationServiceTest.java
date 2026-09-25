package com.ecomdemo.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.notification.Notification;
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
 * What this consumer does with an event, once somebody has decided it is new.
 *
 * <p>Whether a redelivery really happens, and what a rebalance does, are claims about Kafka and
 * are made in {@code OrderPlacedKafkaIT} against a real one.
 *
 * <p><strong>Narrower since Phase 19.</strong> The mechanics of recognising a duplicate — the
 * marker's primary key, writing it before the work, reusing the id that came in the message — moved
 * into {@code EventDeduplicator} in the messaging module, and so did the tests that pin them down:
 * see {@code EventDeduplicatorTest}. Nothing was dropped; it is asserted where the behaviour now
 * lives, which is also where the second consumer this application grows will inherit it.
 *
 * <p>What is left here is this class's own share of the rule: ask, and act on the answer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService")
class NotificationServiceTest {

    @Mock
    private NotificationRepository notifications;

    @Mock
    private EventDeduplicator deduplicator;

    @InjectMocks
    private NotificationService notificationService;

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
        when(deduplicator.claim(eq(eventId), anyString())).thenReturn(true);

        boolean handled = notificationService.handle(event(eventId, 42L));

        assertThat(handled).isTrue();
        verify(notifications).save(notificationCaptor.capture());
        Notification written = notificationCaptor.getValue();
        assertThat(written.getOrderId()).isEqualTo(42L);
        assertThat(written.getRecipient()).isEqualTo("alice");
        assertThat(written.getMessage()).contains("42").contains("2499.50").contains("3 item");
    }

    @Test
    @DisplayName("claims the event by the id FROM THE MESSAGE, not one of its own")
    void claimsWithTheEventIdFromTheMessage() {
        // The single most breakable line in the design, and still worth asserting from this side:
        // an id generated here would be different on every delivery and would recognise no
        // duplicate at all, while a test that only checked "something was claimed" would pass.
        UUID eventId = UUID.randomUUID();
        when(deduplicator.claim(eq(eventId), anyString())).thenReturn(true);

        notificationService.handle(event(eventId, 42L));

        verify(deduplicator).claim(eventId, "OrderPlacedEvent");
    }

    @Test
    @DisplayName("does nothing at all for an event it has already handled")
    void ignoresADuplicate() {
        UUID eventId = UUID.randomUUID();
        when(deduplicator.claim(eq(eventId), anyString())).thenReturn(false);

        boolean handled = notificationService.handle(event(eventId, 42L));

        assertThat(handled).isFalse();
        verify(notifications, never()).save(any());
    }

    @Test
    @DisplayName("claims the event BEFORE writing the notification")
    void claimsBeforeDoingTheWork() {
        // Ordering still matters here even though the marker itself is somebody else's business:
        // if the notification were written first, a consumer that lost the race for the claim
        // would already have written it, and the unique constraint on order_id would be the only
        // thing left standing between the design and two notifications.
        UUID eventId = UUID.randomUUID();
        when(deduplicator.claim(eq(eventId), anyString())).thenReturn(true);

        notificationService.handle(event(eventId, 42L));

        var inOrder = org.mockito.Mockito.inOrder(deduplicator, notifications);
        inOrder.verify(deduplicator).claim(any(), anyString());
        inOrder.verify(notifications).save(any());
    }
}
