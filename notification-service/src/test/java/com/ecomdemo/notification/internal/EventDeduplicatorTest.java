package com.ecomdemo.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * The idempotency mechanics, now that they belong to the messaging module.
 *
 * <p>These assertions were {@code NotificationServiceTest}'s until Phase 19 moved the behaviour
 * here. They did not come along for tidiness: this is the rule every future consumer inherits
 * rather than reimplements, so it is the place it has to be pinned down.
 *
 * <p>What a redelivery actually is, and that Kafka really produces one, is asserted against a real
 * broker in {@code OrderPlacedKafkaIT}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventDeduplicator")
class EventDeduplicatorTest {

    @Mock
    private ProcessedEventRepository processedEvents;

    @InjectMocks
    private EventDeduplicator deduplicator;

    @Captor
    private ArgumentCaptor<ProcessedEvent> processedEventCaptor;

    @Test
    @DisplayName("claims an event nobody has handled")
    void claimsAnUnseenEvent() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);

        assertThat(deduplicator.claim(eventId, "OrderPlacedEvent")).isTrue();
    }

    @Test
    @DisplayName("records the event id FROM THE MESSAGE, not one of its own")
    void recordsTheEventIdItWasGiven() {
        // The single most breakable line in the design. An id generated here - or a database
        // sequence - would be different on every delivery and would recognise no duplicate at
        // all, while every test that only checked "a marker was saved" would still pass.
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);

        deduplicator.claim(eventId, "OrderPlacedEvent");

        verify(processedEvents).save(processedEventCaptor.capture());
        assertThat(processedEventCaptor.getValue().getEventId()).isEqualTo(eventId);
        assertThat(processedEventCaptor.getValue().getEventType()).isEqualTo("OrderPlacedEvent");
    }

    @Test
    @DisplayName("refuses an event it has already recorded, and writes nothing")
    void refusesADuplicate() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(true);

        assertThat(deduplicator.claim(eventId, "OrderPlacedEvent")).isFalse();

        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("writes the marker as part of claiming, so the caller cannot forget to")
    void theMarkerIsWrittenByTheClaim() {
        // The reason this is one method rather than two calls at the call site. The marker's
        // primary key is the event id, so two consumers racing on the same record do not both
        // pass existsById and proceed - the one that inserts second is refused by the database
        // and its transaction rolls back, taking its half-done work with it. That only holds if
        // the marker goes in before the work, which a caller doing this by hand can get wrong.
        UUID eventId = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);

        deduplicator.claim(eventId, "OrderPlacedEvent");

        verify(processedEvents).save(any(ProcessedEvent.class));
    }
}
