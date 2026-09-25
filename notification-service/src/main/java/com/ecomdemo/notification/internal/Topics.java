package com.ecomdemo.notification.internal;

/**
 * The topic names this service reads, and the group it reads them in.
 *
 * <p>Its own copy, for the reason {@code OrderPlacedEvent} is its own copy. A topic name is a string
 * two services must agree on with no compiler between them, so it is a constant on each side rather
 * than a shared class — and the smoke test is what proves they still agree, because nothing else can.
 *
 * <p>The retry and DLT suffixes are here too. They are Spring Kafka's convention rather than a choice,
 * but a consumer that spells them differently from the topics it actually creates gets a silence that
 * looks exactly like "no messages".
 */
final class Topics {

    static final String ORDERS_PLACED = "orders.placed";

    static final String RETRY_SUFFIX = "-retry";

    static final String DLT_SUFFIX = "-dlt";

    /**
     * The consumer group. It is unchanged from when this listener lived in the application, and that
     * matters: a new group id would re-read the topic from the beginning and send a notification for
     * every order ever placed.
     */
    static final String NOTIFICATION_GROUP = "ecomdemo-notification";

    private Topics() {
    }
}
