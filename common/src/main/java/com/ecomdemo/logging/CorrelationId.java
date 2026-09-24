package com.ecomdemo.logging;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The name of the correlation ID, in the three places it is spelled, and the rules for a value
 * that is allowed to come from outside.
 *
 * <p><strong>Why a correlation ID exists.</strong> A log line on its own says what happened; it
 * does not say what the user was doing when it happened. One HTTP request produces lines from a
 * controller, a service, Hibernate and Spring Security, interleaved with the lines of every other
 * request being served at the same time, and the only thing they have in common by default is a
 * thread name — which is reused within milliseconds and means nothing once work is aggregated
 * from several containers. A correlation ID is the shared field that turns those scattered lines
 * back into one story, and it is the field this phase's Loki query is built on.
 *
 * <p><strong>Why the three spellings differ.</strong> The header is {@code X-Correlation-Id}
 * because that is what HTTP conventions look like and what a client will send. The MDC key is
 * {@code correlation_id} because that is what appears verbatim as a JSON field name in every log
 * line, and Loki's label and field syntax has no room for a hyphen. They are deliberately not
 * derived from each other: a rename on one side should be a visible change on the other.
 */
public final class CorrelationId {

    /**
     * The request and response header. Read if the caller sent one, always written back.
     *
     * <p>{@code X-} prefixed headers were deprecated for standards-track names by RFC 6648, and
     * there is no registered standard header for this. {@code X-Correlation-Id} and
     * {@code X-Request-Id} are the two de facto spellings; this project picks one and keeps it.
     * What matters far more than the choice is that the same name is used by every service that
     * will exist from Phase 20 onwards, because an ID that is renamed at a boundary is an ID that
     * stops correlating exactly where correlating starts to be hard.
     */
    public static final String HEADER = "X-Correlation-Id";

    /**
     * The key under which the ID is put into SLF4J's {@link org.slf4j.MDC}, and therefore the
     * JSON field name in the structured log output.
     *
     * <p>Flat, with an underscore, rather than a dotted {@code correlation.id}: Spring Boot's ECS
     * formatter turns a dotted MDC key into a NESTED JSON object, so {@code correlation.id} would
     * be written as {@code "correlation":{"id":"..."}} and every Loki query would have to reach
     * through the object. One field, one name, no nesting.
     */
    public static final String MDC_KEY = "correlation_id";

    /**
     * What an inbound ID is allowed to look like: 8 to 64 characters of letters, digits, hyphen
     * and underscore.
     *
     * <p>This is the security half of the feature, and it is not optional. An ID that arrives in
     * a header and is written into a log line is attacker-controlled text landing in a file that
     * humans and machines both read. Without a pattern, a caller can send a newline and forge an
     * entire extra log entry ("log injection"), send a megabyte and make every line of the
     * request enormous, or send terminal escape codes that execute when somebody cats the file.
     * Bounded charset and bounded length remove all three at once.
     *
     * <p>Note the direction of the check: an ID that fails it is REPLACED, not rejected. The
     * request is not the caller's mistake to pay for — a 400 here would break a client over a
     * field that exists purely for our own diagnostics.
     */
    public static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private CorrelationId() {
    }

    /**
     * A new ID for a request that arrived without a usable one.
     *
     * <p>A random UUID rather than a counter: there is no coordination between containers, so a
     * counter would collide the moment there are two of them, and the collision would silently
     * merge two unrelated requests into one story. The hyphens are dropped because the value is
     * read far more often than it is generated — a 32-character token is easier to select with a
     * double click and shorter in every log line that carries it.
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Returns the caller's ID if it is safe to log, otherwise a fresh one.
     */
    public static String sanitize(String candidate) {
        return candidate != null && ALLOWED.matcher(candidate).matches() ? candidate : generate();
    }
}
