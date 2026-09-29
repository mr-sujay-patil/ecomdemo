-- Phase 32: a third outcome, VOIDED.
--
-- The order service's saga deadline asks this service to SETTLE an order that has waited too long:
-- "tell me what happened, and if nothing has happened, make sure nothing ever will". The answer to
-- the second half is a VOIDED row. It is not a charge and it is not a decline - no card was asked -
-- but it occupies the order's one payment slot (uq_payment_order), so a StockReserved that arrives
-- late, or is replayed from the dead-letter topic, finds a payment already there and charges nothing.
ALTER TABLE payment DROP CONSTRAINT ck_payment_status;
ALTER TABLE payment ADD CONSTRAINT ck_payment_status
    CHECK (status IN ('COMPLETED', 'FAILED', 'VOIDED'));

-- A decline and a void must both say why; a success has nothing to explain.
ALTER TABLE payment DROP CONSTRAINT ck_payment_reason;
ALTER TABLE payment ADD CONSTRAINT ck_payment_reason
    CHECK ((status = 'COMPLETED') = (reason IS NULL));
