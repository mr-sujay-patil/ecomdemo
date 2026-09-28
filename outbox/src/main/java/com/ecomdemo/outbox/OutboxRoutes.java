package com.ecomdemo.outbox;

import java.util.HashMap;
import java.util.Map;

/**
 * Which topic each of a service's outbox events is published to.
 *
 * <p>Every service that uses the outbox declares exactly one of these as a {@code @Bean}. The relay
 * asks it for a row's topic at publication time, by the row's {@code event_type}.
 *
 * <h2>Why a bean, and not a {@code topic} column</h2>
 *
 * <p>Phase 18 decided that the topic is a deployment detail and not part of the fact: renaming a
 * topic, or splitting one in two, should be a change to code and not a migration that rewrites the
 * history in {@code outbox_event}. It implemented that as an {@code if} inside the relay, which
 * was fine while the relay belonged to one service with one event.
 *
 * <p>Phase 24 moved the relay into a library, and a library cannot contain that {@code if}: it
 * would have to know every event of every service that uses it. So the decision stays and the
 * mapping moves to where the knowledge is. The service that writes an event says where it goes;
 * the library only asks.
 *
 * <p>An event type with no route is a deployment that forgot one. The relay fails that row loudly
 * rather than skipping it (see {@code OutboxBatchPublisher}).
 */
public final class OutboxRoutes {

    private final Map<String, String> topicsByEventType;

    private OutboxRoutes(Map<String, String> topicsByEventType) {
        this.topicsByEventType = Map.copyOf(topicsByEventType);
    }

    /** Starts an empty set of routes. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The topic for an event type, or {@code null} if this service never declared one.
     *
     * @param eventType the simple class name stored in {@code outbox_event.event_type}
     */
    public String topicFor(String eventType) {
        return topicsByEventType.get(eventType);
    }

    /** Collects routes keyed by the event's class, so a rename cannot leave a stale string behind. */
    public static final class Builder {

        private final Map<String, String> routes = new HashMap<>();

        private Builder() {
        }

        /** Publishes every {@code eventClass} written to the outbox on {@code topic}. */
        public Builder route(Class<?> eventClass, String topic) {
            String previous = routes.putIfAbsent(eventClass.getSimpleName(), topic);
            if (previous != null) {
                throw new IllegalStateException(
                        eventClass.getSimpleName() + " is already routed to " + previous);
            }
            return this;
        }

        public OutboxRoutes build() {
            return new OutboxRoutes(routes);
        }
    }
}
