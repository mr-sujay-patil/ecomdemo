-- The cart and the order stop pointing at an account row.
--
-- `users` goes to customer-service in Phase 20d, so these two foreign keys have nothing left to
-- point at. THE COLUMNS DO NOT CHANGE: `cart.user_id` and `orders.user_id` have held the same
-- numbers since V6, and the repository queries already filtered on them rather than walking the
-- association. Only the constraints go, which is why this migration is safe to apply while the
-- application is still one deployable - there is no data to move and nothing to backfill.
--
-- WHAT IS LOST, said plainly: the database guaranteed that every cart and every order belonged to an
-- account that existed. Nothing guarantees that now. Deleting an account leaves its cart and its
-- orders behind, referring to an id nobody can resolve.
--
-- That is the same trade V12 made for `cart_item.product_id`, and it is the price of a database per
-- service rather than an oversight. An ORDER is the place it matters least: it already snapshots the
-- username, the product name and the price it charged, precisely so that what a customer was billed
-- cannot be rewritten by editing something else afterwards. A CART is the place it matters most, and
-- the honest answer is that an orphaned cart is harmless - nobody can log in as a deleted account to
-- see it - but nothing reaps it either. Recorded in docs/decisions.md.
--
-- The alternative was to keep the accounts in this database and have customer-service read them,
-- which is not a split at all.
-- FIRST the snapshot, and this is the part with a deadline.
--
-- `Order.getUsername()` used to read through the association, which is what the API response and the
-- audit row both show. A claim on the token can supply it for NEW orders, but the ones already in
-- this table have no username anywhere - and once `users` lives in customer-service there is no query
-- that can fill it in. No join, no subselect: ten thousand HTTP calls, or nothing.
--
-- So it is backfilled here, in the last migration that can see both tables. This is the 20a lesson at
-- its sharpest: a data change needing both halves has exactly ONE window, and it closes when the
-- services separate.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS username VARCHAR(50);

-- A CORRELATED SUBQUERY, not `UPDATE ... FROM`. The latter is PostgreSQL syntax and H2 rejects it,
-- and these migrations have to run on both: the repository slice tests apply the real Flyway history
-- to H2, which is what makes a migration's SQL part of what the fast suite proves. The portable form
-- is no slower here - one row per order either way - and it fails in the build rather than in a
-- container.
UPDATE orders
   SET username = (SELECT u.username FROM users u WHERE u.id = orders.user_id)
 WHERE username IS NULL;

-- Any order whose account has somehow already gone gets a marker rather than a null, because the
-- column is about to be NOT NULL and a silent failure here would be a migration that cannot be
-- re-run. A readable placeholder in one row beats a broken deploy.
UPDATE orders SET username = 'unknown-' || user_id WHERE username IS NULL;

ALTER TABLE orders ALTER COLUMN username SET NOT NULL;

-- THEN the constraints go.
ALTER TABLE cart   DROP CONSTRAINT IF EXISTS fk_cart_user;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS fk_orders_user;

-- The indexes that backed those keys are NOT dropped. A foreign key and an index are different
-- things: the key was the guarantee, the index is what makes "this customer's cart" a lookup rather
-- than a scan, and both queries still filter on user_id on every request.
