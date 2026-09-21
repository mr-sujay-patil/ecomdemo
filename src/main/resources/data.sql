-- Seed catalogue. Runs after Hibernate creates the schema, thanks to
-- spring.jpa.defer-datasource-initialization.
--
-- Until Phase 4 this was a plain INSERT: the H2 database was in-memory and started empty on every
-- run, so re-seeding was free. PostgreSQL keeps its data, and `spring.sql.init.mode=always` runs
-- this script on every start - a plain INSERT would add ten more products each time you restart.
--
-- The guard makes the script idempotent: the whole thing is ONE statement, so `NOT EXISTS` is
-- evaluated once, against the table as it was before the insert began. All ten rows are inserted
-- into an empty catalogue, and nothing is inserted into a catalogue that already has rows.
-- Phase 5 replaces this script with a Flyway migration, which tracks what has already been
-- applied instead of re-deciding it on every boot.

INSERT INTO product (name, description, price, stock_quantity)
SELECT * FROM (VALUES
  ('Mechanical Keyboard',   'Hot-swappable 75% keyboard with tactile switches',      8999.00, 25),
  ('Wireless Mouse',        'Ergonomic mouse, 6 buttons, USB-C rechargeable',        2499.50, 40),
  ('27" 4K Monitor',        '27-inch IPS display, 3840x2160, 60Hz, USB-C power',    32999.00, 12),
  ('Noise-Cancelling Headphones', 'Over-ear ANC headphones, 30h battery',           14999.00, 18),
  ('USB-C Hub',             '7-in-1 hub: HDMI, Ethernet, SD, 3x USB-A',              3499.00, 55),
  ('Laptop Stand',          'Adjustable aluminium stand, up to 16-inch laptops',     2199.00, 33),
  ('Webcam 1080p',          'Full HD webcam with dual microphones and privacy cover',4599.00,  9),
  ('Desk Mat',              'Large felt and cork desk mat, 900x400mm',               1299.00, 60),
  ('Portable SSD 1TB',      'USB 3.2 Gen 2 external SSD, 1050 MB/s read',            9499.00, 15),
  ('Laptop Sleeve 16"',     'Water-resistant padded sleeve for 16-inch laptops',     1799.00,  2)
) AS seed (name, description, price, stock_quantity)
WHERE NOT EXISTS (SELECT 1 FROM product);
