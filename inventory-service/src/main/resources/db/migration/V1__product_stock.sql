-- inventory_db, from nothing: one table, because one table is all this service owns.
--
-- ------------------------------------------------------------------------------------------
-- WHY THIS STARTS AT V1 AGAIN
-- ------------------------------------------------------------------------------------------
-- The monolith's history runs to V12 and is NOT carried forward. A Flyway history describes one
-- database, and this is a different database - it has never had a `product` table, never had a
-- `cart`, and applying twelve migrations to build one table would be describing a past this
-- schema did not have.
--
-- What IS carried forward is the shape, verbatim from V11 of the monolith, because the data has
-- to survive the move. The column names, the types and the version column are identical; a
-- migration that "improved" them here would make the cutover a data change as well as a
-- deployment change.
--
-- ------------------------------------------------------------------------------------------
-- STILL NO FOREIGN KEY, and now it is not even possible
-- ------------------------------------------------------------------------------------------
-- V11 left `product_stock.product_id` without a REFERENCES clause and said why: the tables were
-- about to be in different databases. They now are. The decision that read as caution two
-- migrations ago reads as arithmetic here - there is no `product` table in this database to point
-- at, and there never will be.
--
-- The application rule that replaces it is unchanged: a product with no stock row has zero, which
-- is both true and an answer a caller can act on. See InventoryService.
CREATE TABLE product_stock (
    -- The product's id, assigned by catalog-service and simply believed here. That is what a
    -- foreign key was doing before, and what nothing does now: this service cannot check that the
    -- id refers to a real product, and does not need to - it counts things, and counting an id
    -- nobody ever asks about costs one unused row.
    product_id BIGINT  PRIMARY KEY,

    quantity   INTEGER NOT NULL,

    -- The optimistic lock, which is the whole reason stock got its own row in Phase 20a. Two
    -- checkouts racing for the last unit collide here; a checkout and an administrator editing a
    -- description no longer collide at all, because the description is in another database.
    version    BIGINT  NOT NULL DEFAULT 0
);
