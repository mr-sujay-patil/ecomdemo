-- Phase 24: the idempotent-consumer table, back in the order service.
--
-- V9 created `processed_event` for the notification consumer, which then lived in this
-- application. Phase 20d moved that consumer to notification-service, and V16 dropped the table
-- with it - this service consumed nothing any more.
--
-- The saga changes that. The order service now LISTENS for the replies to its own checkout -
-- StockRejected, PaymentCompleted, PaymentFailed - and Kafka will deliver each of them at least
-- once. Confirming an order twice would publish two OrderPlaced events and send two e-mails, so
-- the table comes back, owned now by the shared `outbox` library (`ProcessedEvents`), which every
-- service in the saga uses the same way.
--
-- Same shape as V9's and notification-service's: the producer's event id IS the primary key, so a
-- second delivery cannot be inserted - the database, not a check in Java, is what refuses it.
CREATE TABLE processed_event (
    event_id     UUID                        PRIMARY KEY,
    event_type   VARCHAR(100)                NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- For a future sweep of old markers; nothing deletes them yet (a follow-up in the test report).
CREATE INDEX idx_processed_event_processed_at ON processed_event (processed_at);
