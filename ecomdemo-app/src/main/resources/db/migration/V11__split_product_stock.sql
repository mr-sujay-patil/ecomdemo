-- Splits stock out of the product table, which is the change Phase 19 deferred to here by name.
--
-- ------------------------------------------------------------------------------------------
-- WHY NOW, and not in Phase 19
-- ------------------------------------------------------------------------------------------
-- Phase 19 separated catalogue from inventory as MODULES and deliberately left them sharing one
-- table, recording the cost in StockMutationRulesTest: the ArchUnit rule had to permit two
-- modules to write stock rather than one, because `catalog` owns the entity and cannot give up
-- access to a column on it. That test says, in as many words, that splitting `product` and
-- `product_stock` is what would let the rule name a single module and that Phase 20 is where the
-- question belongs.
--
-- Phase 20 is not free to defer it again. The two modules become two SERVICES with two DATABASES,
-- and a column cannot be in two databases.
--
-- ------------------------------------------------------------------------------------------
-- THE DEPENDENCY THIS INVERTS, which is the interesting part
-- ------------------------------------------------------------------------------------------
-- Until now `inventory` depended on `catalog`: it held a Product, mutated a field on it, and
-- saved through ProductService. After this migration inventory deals in product IDS and
-- quantities and never sees a Product at all - so that edge disappears, and the only remaining
-- one runs the other way, `catalog` asking inventory for the number it needs to answer an API
-- call.
--
-- That is not a tidy-up. It is the shape the services have to have: inventory-service cannot know
-- what a catalogue is, because it will not have one. Splitting the table is what makes the code
-- admit that.
--
-- ------------------------------------------------------------------------------------------
-- NO FOREIGN KEY, deliberately
-- ------------------------------------------------------------------------------------------
-- `product_stock.product_id` is the id of a row in `product`, and there is no REFERENCES clause
-- on it. Inside this monolith one would work and would be free. Two migrations from now these
-- tables live in different databases, where a foreign key is not a thing that can exist - so
-- adding one here would buy a few commits of referential integrity in exchange for writing a
-- migration to remove it, and for teaching the code to rely on a guarantee that is about to be
-- withdrawn.
--
-- What replaces it is the same thing that will have to replace it later: the application creates
-- the stock row when it creates the product, and a missing stock row is treated as zero rather
-- than as an error. See InventoryService.
--
-- The same portable dialect every migration since V1 uses - this runs on H2 in the fast suite and
-- on PostgreSQL everywhere else, and Flyway translates neither.
CREATE TABLE product_stock (
    -- The PRODUCT's id, reused as this table's primary key rather than a surrogate of its own.
    -- One stock row per product is the invariant, and a primary key states it for free; a
    -- surrogate key plus a unique constraint would say the same thing in two places.
    product_id BIGINT  PRIMARY KEY,

    quantity   INTEGER NOT NULL,

    -- The optimistic lock MOVES HERE, and that is the point of the whole split.
    --
    -- It was on `product`, where it guarded every column at once: two concurrent checkouts
    -- collided, correctly, but so did a checkout and an administrator editing the description.
    -- Now the lock is on the row that actually experiences contention, and the two kinds of write
    -- stop interfering. ConcurrentCheckoutTest asserts the collision that must still happen.
    version    BIGINT  NOT NULL DEFAULT 0
);

-- Every existing product keeps exactly the stock it had. This runs inside Flyway's transaction
-- with the DROP below, so there is no instant at which the number exists in neither place.
INSERT INTO product_stock (product_id, quantity, version)
SELECT id, stock_quantity, 0 FROM product;

ALTER TABLE product DROP COLUMN stock_quantity;
