-- The ten products a fresh `docker compose up` has to sell.
--
-- CONSOLIDATED, and one column SHORTER than the seed it replaces. The application's original
-- V2__seed_products.sql inserted a `stock_quantity` and its V3 then went back and set categories
-- with four UPDATE statements. Neither is replayed: stock is inventory-service's column now, and a
-- category the very next migration overwrites is history, not data.
--
-- THE IDS ARE EXPLICIT, and that is new. They were implicit before - the identity column handed out
-- 1..10 because this was the first insert into an empty table, and inventory_db's own seed was
-- written against those numbers. Leaving it implicit would have kept working and kept being a
-- coincidence; writing the ids down makes the cross-database agreement a stated fact that
-- `SeededStockAgreesWithTheCatalogueTest` can check.
--
-- If this list changes, inventory-service's V2 has to change with it. There is no foreign key that
-- can say so - the two tables are in different databases - so a test compares the two files.
INSERT INTO product (id, name, description, price, category) VALUES
  ( 1, 'Mechanical Keyboard',         'Hot-swappable 75% keyboard with tactile switches',       8999.00, 'PERIPHERALS'),
  ( 2, 'Wireless Mouse',              'Ergonomic mouse, 6 buttons, USB-C rechargeable',         2499.50, 'PERIPHERALS'),
  ( 3, '27" 4K Monitor',              '27-inch IPS display, 3840x2160, 60Hz, USB-C power',     32999.00, 'DISPLAYS'),
  ( 4, 'Noise-Cancelling Headphones', 'Over-ear ANC headphones, 30h battery',                  14999.00, 'AUDIO'),
  ( 5, 'USB-C Hub',                   '7-in-1 hub: HDMI, Ethernet, SD, 3x USB-A',               3499.00, 'ACCESSORIES'),
  ( 6, 'Laptop Stand',                'Adjustable aluminium stand, up to 16-inch laptops',      2199.00, 'ACCESSORIES'),
  ( 7, 'Webcam 1080p',                'Full HD webcam with dual microphones and privacy cover',  4599.00, 'PERIPHERALS'),
  ( 8, 'Desk Mat',                    'Large felt and cork desk mat, 900x400mm',                1299.00, 'ACCESSORIES'),
  ( 9, 'Portable SSD 1TB',            'USB 3.2 Gen 2 external SSD, 1050 MB/s read',             9499.00, 'STORAGE'),
  (10, 'Laptop Sleeve 16"',           'Water-resistant padded sleeve for 16-inch laptops',      1799.00, 'ACCESSORIES');

-- An explicit id does NOT advance the identity sequence, so without this the next product created
-- through the API would be handed id 1 and collide with the keyboard. This is the standard tax on
-- seeding explicit ids, and forgetting it fails at the first POST rather than here.
SELECT setval(pg_get_serial_sequence('product', 'id'), (SELECT MAX(id) FROM product));
