package com.ecomdemo.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.outbox.internal.ProcessedEvent;
import com.ecomdemo.outbox.internal.ProcessedEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The claim's two answers. That it joins the caller's transaction ({@code MANDATORY}) is a claim
 * about Spring's proxy, and is proved by each service's listener integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessedEvents")
class ProcessedEventsTest {

    @Mock
    private ProcessedEventRepository repository;

    private ProcessedEvents processedEvents;

    @BeforeEach
    void setUp() {
        processedEvents = new ProcessedEvents(repository);
    }

    @Test
    @DisplayName("the first delivery claims the event and records it")
    void firstDeliveryWins() {
        UUID eventId = UUID.randomUUID();
        when(repository.existsById(eventId)).thenReturn(false);

        assertThat(processedEvents.claim(eventId, "StockReservedEvent")).isTrue();

        ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isEqualTo(eventId);
        assertThat(saved.getValue().getEventType()).isEqualTo("StockReservedEvent");
    }

    @Test
    @DisplayName("a redelivery of the same event is refused and records nothing")
    void redeliveryIsRefused() {
        UUID eventId = UUID.randomUUID();
        when(repository.existsById(eventId)).thenReturn(true);

        assertThat(processedEvents.claim(eventId, "StockReservedEvent")).isFalse();

        verify(repository, never()).save(any());
    }
}
