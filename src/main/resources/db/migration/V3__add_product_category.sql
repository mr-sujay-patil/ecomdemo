-- V3: give every product a category, and an index to filter by it.
--
-- This migration exists to practise schema evolution: V1 and V2 built what already existed, V3
-- changes it while the application is live.
--
-- WHY THE COLUMN IS NULLABLE
-- During a deploy there is a window where the new schema is already in place and instances of
-- the OLD application version are still running and still inserting products - with no idea that
-- `category` exists. A `NOT NULL` column without a default would make every one of those inserts
-- fail, so the deploy takes the site down instead of rolling through it.
--
-- A nullable column (or a NOT NULL one with a DEFAULT) is BACKWARD-COMPATIBLE: old code keeps
-- working, new code can start writing the column, and only once every instance writes it is it
-- safe to tighten the constraint in a later migration. That is the expand/contract pattern, and
-- it is the whole reason migrations are split into small steps instead of one big ALTER.
ALTER TABLE product ADD COLUMN category VARCHAR(50);

-- Backfill what the catalogue already holds. UPDATE ... WHERE name IN (...) is safe to write
-- here because V2 is the only thing that has inserted products, and Flyway guarantees V2 ran
-- before V3. Anything a client created since then is simply left uncategorised.
UPDATE product SET category = 'PERIPHERALS'
 WHERE name IN ('Mechanical Keyboard', 'Wireless Mouse', 'Webcam 1080p');

UPDATE product SET category = 'DISPLAYS'
 WHERE name IN ('27" 4K Monitor');

UPDATE product SET category = 'AUDIO'
 WHERE name IN ('Noise-Cancelling Headphones');

UPDATE product SET category = 'STORAGE'
 WHERE name IN ('Portable SSD 1TB');

UPDATE product SET category = 'ACCESSORIES'
 WHERE name IN ('USB-C Hub', 'Laptop Stand', 'Desk Mat', 'Laptop Sleeve 16"');

-- "Show me everything in AUDIO" scans the whole table without this. The index costs a little on
-- every write and some disk; on a catalogue that is read far more often than written, that is
-- the right trade. It is created in the same migration as the column so that no deploy can ever
-- run the query without it.
CREATE INDEX idx_product_category ON product (category);
