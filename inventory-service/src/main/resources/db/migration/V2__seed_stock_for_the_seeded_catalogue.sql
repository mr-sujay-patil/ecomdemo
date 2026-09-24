-- Stock for the ten products catalog-service seeds in ITS V2__seed_products.sql.
--
-- WHY THIS MIGRATION HAD TO EXIST, and how its absence showed up. The extraction moved the
-- product_stock TABLE to this database and left its CONTENTS behind: inventory_db started at V1
-- with an empty table, so every seeded product reported zero stock. Nothing failed. The catalogue
-- listed all ten products happily, each showing "0 in stock", and the first thing that noticed was
-- the smoke test failing to find a product it could buy two of - twenty checks down, with a Python
-- traceback about a decimal, three layers from the cause.
--
-- The lesson is worth more than the file: splitting a database splits its DATA, not only its DDL,
-- and seed data is data. A real cutover has the same shape and no seed script to fall back on.
--
-- THE IDS ARE HARD-CODED, and that is the uncomfortable part. These ten rows must line up with the
-- ids the catalogue's own seed produces, and nothing enforces it - there is no foreign key across
-- databases, and a missing row reads as zero rather than as an error (a Phase 20a decision). It
-- works because both seeds run once against an empty database and the catalogue's is the first
-- insert, so its identity column yields 1..10. Anything more robust would mean one service reading
-- the other's table, which is the coupling the split exists to remove. What makes it safe enough is
-- that these are SEED rows: they exist so a fresh `docker compose up` has something to sell, and
-- real stock arrives through the API.
--
-- The quantities are copied verbatim from the catalogue's seed. If that file changes, this one has
-- to change with it, and `SeededStockAgreesWithTheCatalogueTest` is what says so out loud.
INSERT INTO product_stock (product_id, quantity, version) VALUES
  ( 1, 25, 0),   -- Mechanical Keyboard
  ( 2, 40, 0),   -- Wireless Mouse
  ( 3, 12, 0),   -- 27" 4K Monitor
  ( 4, 18, 0),   -- Noise-Cancelling Headphones
  ( 5, 55, 0),   -- USB-C Hub
  ( 6, 33, 0),   -- Laptop Stand
  ( 7,  9, 0),   -- Webcam 1080p
  ( 8, 60, 0),   -- Desk Mat
  ( 9, 15, 0),   -- Portable SSD 1TB
  (10,  2, 0);   -- Laptop Sleeve 16"
