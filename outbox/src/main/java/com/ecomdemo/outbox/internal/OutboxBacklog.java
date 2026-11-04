package com.ecomdemo.outbox.internal;

import java.time.Instant;

/**
 * What the outbox holds that has not been published (KI-031).
 *
 * @param pending how many events wait
 * @param oldest  the oldest waiting event's {@code created_at}, or {@code null} when none waits
 */
public record OutboxBacklog(long pending, Instant oldest) {
}
