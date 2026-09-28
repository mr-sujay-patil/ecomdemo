package com.ecomdemo.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxRoutes")
class OutboxRoutesTest {

    @Test
    @DisplayName("finds a topic by the event type stored in the row")
    void routesByEventType() {
        OutboxRoutes routes =
                OutboxRoutes.builder().route(SampleOrderEvent.class, "orders.placed").build();

        // The relay only ever has the STRING from outbox_event.event_type, never the class.
        assertThat(routes.topicFor("SampleOrderEvent")).isEqualTo("orders.placed");
    }

    @Test
    @DisplayName("has no topic for an event the service never routed")
    void unknownTypeHasNoTopic() {
        OutboxRoutes routes =
                OutboxRoutes.builder().route(SampleOrderEvent.class, "orders.placed").build();

        assertThat(routes.topicFor("SomethingElseHappened")).isNull();
    }

    @Test
    @DisplayName("refuses to route one event type to two topics")
    void refusesADuplicateRoute() {
        // The relay could only honour one of them; failing at startup beats a coin toss.
        OutboxRoutes.Builder builder =
                OutboxRoutes.builder().route(SampleOrderEvent.class, "orders.placed");

        assertThatThrownBy(() -> builder.route(SampleOrderEvent.class, "orders.created"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already routed to orders.placed");
    }
}
