-- Phase 33: login throttling. One row per thing being throttled: a username ('user:asha') or a
-- client address ('client:203.0.113.7'), with its recent failures and, once it has too many, the
-- time it is blocked until.
--
-- In the database rather than in memory so that every customer-service replica counts the same
-- failures, and a restart does not forgive an attacker. Rows are small and short-lived: a success
-- deletes a username's row, and a row whose window and block are both over is reset on its next use.
CREATE TABLE login_throttle (
    throttle_key      VARCHAR(120)                NOT NULL PRIMARY KEY,
    failures          INTEGER                     NOT NULL,
    window_started_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    blocked_until     TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT ck_login_throttle_failures CHECK (failures >= 0)
);
