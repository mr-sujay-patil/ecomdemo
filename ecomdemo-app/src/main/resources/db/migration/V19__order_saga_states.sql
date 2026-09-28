-- Phase 24: an order's status starts meaning something.
--
-- V1 created `status` with a CHECK allowing only 'PLACED', and said that a new status would mean a
-- migration that drops and recreates the constraint. This is that migration.
--
-- Checkout used to reserve stock over HTTP and could say "placed" the moment it returned. In the
-- saga it cannot: it creates the order PENDING, and inventory-service and payment-service decide
-- the rest, asynchronously. So there are three states now - see OrderStatus for the diagram.

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;

-- Every existing order was placed under the old, synchronous rules: its stock was taken and it
-- was never going to be cancelled. CONFIRMED is what those rows mean in the new vocabulary.
-- Rewritten by NAME, which is only possible because V1 chose @Enumerated(STRING) over ordinals.
UPDATE orders SET status = 'CONFIRMED' WHERE status = 'PLACED';

ALTER TABLE orders ADD CONSTRAINT ck_orders_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'));

-- Why a CANCELLED order was cancelled ("Insufficient stock for ...", "Payment declined: ..."),
-- straight from the event that cancelled it. NULL for the other two states.
ALTER TABLE orders ADD COLUMN status_reason VARCHAR(500);

-- When the saga moved the order out of PENDING. NULL while pending, and NULL for the rows migrated
-- above: nobody recorded when those were decided, and inventing a time would be a false record.
ALTER TABLE orders ADD COLUMN status_changed_at TIMESTAMP(6) WITH TIME ZONE;

-- The saga's replies find an order by id; this index is for the other question the status now
-- makes worth asking - "which orders are stuck PENDING?" - which a saga timeout would ask.
CREATE INDEX idx_orders_status ON orders (status);
