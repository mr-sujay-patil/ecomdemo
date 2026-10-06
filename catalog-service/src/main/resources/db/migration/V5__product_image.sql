-- Product images (Phase 34, the web team's KI-002), SEED-ONLY.
--
-- A product can name one image file. The column holds a plain FILE NAME, never a path or a URL: the
-- file ships inside this service (classpath `product-images/`), the API derives the public URL
-- (`/api/products/{id}/image`) from the product id, and the server only ever opens a name that has
-- an allow-listed extension and no path separator. Nothing writes this column through the API;
-- `ProductRequest` did not change, so a product created or replaced through the API has no image
-- (NULL), which every client must keep handling.
--
-- NULL is deliberate for products 9 and 10 below too: real data on the "no image" path, so the
-- placeholder case is exercised by the seed and not only by a test fixture.
--
-- Not in `V2__seed_products.sql`: that file is applied history, and changing an applied migration
-- changes its checksum, which Flyway refuses.
ALTER TABLE product ADD COLUMN image_file VARCHAR(100);

UPDATE product SET image_file = 'mechanical-keyboard.svg'          WHERE id = 1;
UPDATE product SET image_file = 'wireless-mouse.svg'               WHERE id = 2;
UPDATE product SET image_file = 'monitor-4k.svg'                   WHERE id = 3;
UPDATE product SET image_file = 'noise-cancelling-headphones.svg'  WHERE id = 4;
UPDATE product SET image_file = 'usb-c-hub.svg'                    WHERE id = 5;
UPDATE product SET image_file = 'laptop-stand.svg'                 WHERE id = 6;
UPDATE product SET image_file = 'webcam-1080p.svg'                 WHERE id = 7;
UPDATE product SET image_file = 'desk-mat.svg'                     WHERE id = 8;
