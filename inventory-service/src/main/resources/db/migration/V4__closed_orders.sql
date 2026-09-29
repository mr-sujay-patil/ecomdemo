-- Phase 32: orders the saga deadline has closed.
--
-- When the order service gives up on an order, it asks this service to give back whatever the order
-- holds. Releasing is not enough on its own: the OrderCreated that never arrived might still arrive
-- - late, or replayed from orders.created-dlt - and reserve stock for an order that is already
-- CANCELLED, with nobody left to release it. A row here is the fence: reserveForOrder refuses an
-- order that has one.
CREATE TABLE closed_order (
    order_id  BIGINT                      PRIMARY KEY,
    reason    VARCHAR(500)                NOT NULL,
    closed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
