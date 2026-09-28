package com.ecomdemo.messaging.internal;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Keeps the outbox relay's tick out of Tempo, and only the relay's.
 *
 * <p>Spring observes every {@code @Scheduled} run as {@code tasks.scheduled.execution}. The relay
 * runs every second, so each tick was a trace of its own, around the clock, recording that nothing
 * happened. Its spans are not lost information: the relay restores each event's ORIGINAL trace
 * before sending it (see {@link OutboxTracing}), so a published event still shows up inside the
 * checkout that caused it.
 *
 * <h2>Why a predicate, not the property</h2>
 *
 * <p>{@code management.observations.enable.tasks.scheduled=false} does the same for the relay, but
 * a property can only match an observation's NAME, and every scheduled method shares that name. It
 * also silenced {@link OutboxCleanupJob} and the nightly sales report, whose run time and failures
 * are worth having in Prometheus precisely because they run rarely. The predicate sees the context,
 * which knows the class, so it can drop one method's observations and keep the rest.
 *
 * <p>No observation means no span AND no metric. Losing the relay's timer is the accepted cost: a
 * timer that fires every second measured an empty poll almost every time.
 */
@Configuration(proxyBeanMethods = false)
class OutboxObservationConfig {

    @Bean
    ObservationPredicate noOutboxRelayObservations() {
        return (name, context) -> !(context instanceof ScheduledTaskObservationContext task
                && OutboxRelay.class.equals(task.getTargetClass()));
    }
}
