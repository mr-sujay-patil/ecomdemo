-- The cart stops pointing at the catalogue and starts remembering what it was told.
--
-- ------------------------------------------------------------------------------------------
-- WHY: a foreign key cannot cross a service boundary
-- ------------------------------------------------------------------------------------------
-- `cart_item` has held a real foreign key to `product` since V1, and `CartItem` has held a
-- `@ManyToOne Product`. The cart is going to order-service and the product to catalog-service,
-- with a database each - so the constraint has nothing to point at and the association has
-- nothing to load. Neither survives the split, and pretending otherwise until the extraction
-- would mean discovering it halfway through one.
--
-- `order_item` already works this way and needs no change at all. Phase 6 wrote it that way for a
-- different reason - an order is a record of what was bought at the price it was bought at, which
-- must not drift when the catalogue is edited - and that decision turns out to be exactly what a
-- service boundary requires. The cart is now held to the same shape.
--
-- ------------------------------------------------------------------------------------------
-- THE BEHAVIOUR THIS CHANGES, stated plainly rather than discovered later
-- ------------------------------------------------------------------------------------------
-- Before: `CartItem.lineTotal()` multiplied the quantity by `product.getPrice()`, read through
-- the association on every load. A cart therefore always reflected TODAY's catalogue - edit a
-- price and every existing cart silently repriced.
--
-- After: the cart reflects the catalogue AS AT THE MOMENT THE LINE WAS ADDED. Edit a price and
-- existing carts keep the old one until the shopper changes that line.
--
-- That is a real change and it is not strictly better - it is the trade a distributed system
-- makes. A cart that repriced itself would need the catalogue on every read, which is a synchronous
-- call to another service on the hottest path there is. It is also, arguably, the more honest
-- behaviour: the price a shopper was shown is the price they expect at checkout.
--
-- What does NOT change is what the order is charged. OrderPlacementService has always snapshotted
-- price onto `order_item` at checkout, so the money side was already decided there.
--
-- The same portable dialect every migration since V1 uses.

-- Nullable first, because the table has rows and they have no value for these columns yet.
ALTER TABLE cart_item ADD COLUMN product_name VARCHAR(255);
ALTER TABLE cart_item ADD COLUMN unit_price   NUMERIC(12, 2);

-- Backfill from the association that is about to be removed. This is the last moment at which
-- the join is available, which is precisely why the backfill belongs in this migration and not
-- in application code that runs afterwards.
UPDATE cart_item
SET product_name = (SELECT p.name  FROM product p WHERE p.id = cart_item.product_id),
    unit_price   = (SELECT p.price FROM product p WHERE p.id = cart_item.product_id);

-- Now they can be required. A line with no product name is a line nobody can render.
ALTER TABLE cart_item ALTER COLUMN product_name SET NOT NULL;
ALTER TABLE cart_item ALTER COLUMN unit_price   SET NOT NULL;

-- And the constraint goes. `product_id` STAYS - it is still how a line is identified, and the
-- API still takes a product id - but it is a plain number now, the same as `order_item.product_id`
-- has always been.
ALTER TABLE cart_item DROP CONSTRAINT fk_cart_item_product;
