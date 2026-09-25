-- The application stops owning accounts, notifications and the idempotency ledger.
--
-- The last of the drop migrations, and the one that finishes Phase 20. V13 gave up product_stock, V14
-- gave up product, V15 gave up the foreign keys to users. This gives up the three tables themselves:
--
--   users            -> customer-service (customer_db)
--   notification     -> notification-service (notification_db)
--   processed_event  -> notification-service (notification_db)
--
-- The argument is the one V14 made and it has not changed: a leftover copy still answers queries. It
-- would return accounts that cannot log in, notifications nobody sent, and a processed_event ledger
-- that deduplicates nothing - all of it frozen at the moment of the split and drifting further from the
-- truth with every registration. Two services reading two copies of one table is the exact failure this
-- phase exists to prevent.
--
-- ORDER MATTERS, just about. `users` is dropped last because V15's backfill of `orders.username` reads
-- it, and while Flyway applies migrations in order and V15 has already run, a re-run against a restored
-- database would not appreciate the reversal.
DROP TABLE IF EXISTS notification;
DROP TABLE IF EXISTS processed_event;
DROP TABLE IF EXISTS users;

-- WHAT IS LEFT IN THIS DATABASE is what order-service owns: cart, cart_item, orders, order_item,
-- order_audit, outbox_event and the six Spring Batch tables. That is the residue of the monolith, and
-- naming the module `order-service` is now the only step of the split that has not been taken.
