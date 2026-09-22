-- V6: the cart and the order history stop belonging to everybody and start belonging to someone.
--
-- Until now there was exactly one cart row in the database and every visitor added to it. That
-- was not a simplification of a multi-user shop, it was a different application: two people
-- shopping at once saw each other's items and either one could check the other's basket out.
-- V5 added the accounts; this migration attaches the data to them.

-- --------------------------------------------------------------------------------------------
-- cart.user_id: one cart per account
-- --------------------------------------------------------------------------------------------
-- The existing cart rows are deleted rather than migrated. A cart is scratch state — a few
-- minutes of someone's browsing — and there is no correct account to attribute a cart assembled
-- by "everyone" to. Order history, below, is the opposite kind of data and is kept.
--
-- `ON DELETE CASCADE` on cart_item (V1) means emptying `cart` clears the lines with it.
DELETE FROM cart;

-- The column can be declared NOT NULL directly because the table is now empty. That is the only
-- reason: adding a NOT NULL column without a default to a table WITH rows fails, which is why
-- `orders` below has to be done in three steps instead of one.
ALTER TABLE cart ADD COLUMN user_id BIGINT NOT NULL;

-- UNIQUE is the "one cart per account" rule itself, not an optimisation. Without it a bug that
-- inserted a second cart row for a user would show up as items mysteriously disappearing —
-- whichever cart the query happened to return would be missing the other's lines. The database
-- refuses instead. It also gives the index every cart read goes through.
ALTER TABLE cart ADD CONSTRAINT uq_cart_user UNIQUE (user_id);

-- Deleting an account takes its cart with it: a cart has no meaning without its owner.
ALTER TABLE cart ADD CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- --------------------------------------------------------------------------------------------
-- orders.user_id: who placed it
-- --------------------------------------------------------------------------------------------
-- Three steps, because this table may already have rows in a database that has been running
-- since Phase 4:
--   1. add the column nullable, which always succeeds;
--   2. give the existing rows a value;
--   3. only then tighten it to NOT NULL.
-- On a fresh database (the test suite, the integration tests, a new deployment) steps 2 and 3
-- are no-ops over zero rows and the result is identical. A migration has to be correct on both.
ALTER TABLE orders ADD COLUMN user_id BIGINT;

-- Orders placed before there were users belong to nobody in particular. They are attributed to
-- the seeded administrator because that is the only account that certainly exists, and because
-- inventing a "legacy" user would leave a permanent fiction in the accounts table. The
-- alternative — deleting them — throws away the very history this table exists to keep.
UPDATE orders SET user_id = (SELECT id FROM users WHERE username = 'admin') WHERE user_id IS NULL;

ALTER TABLE orders ALTER COLUMN user_id SET NOT NULL;

-- RESTRICT, not CASCADE, and the difference matters. An order is a financial record: it must
-- survive the deletion of the account that placed it, the same way order lines already keep a
-- copy of the product name and price so that repricing cannot rewrite history. Deleting a user
-- who has orders now fails, which is the correct answer — that conversation is about anonymising
-- the account, not about erasing what was sold.
ALTER TABLE orders ADD CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;

-- "My orders" is the only way this table is ever read now, and a foreign key does not create an
-- index in PostgreSQL.
CREATE INDEX idx_orders_user ON orders (user_id);
