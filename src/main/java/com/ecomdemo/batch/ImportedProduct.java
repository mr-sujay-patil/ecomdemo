package com.ecomdemo.batch;

import com.ecomdemo.catalog.Product;

/**
 * What one CSV row becomes: a catalogue product, and the stock level the row asked for.
 *
 * <p><strong>This record exists because Phase 20 split stock out of {@code product}.</strong>
 * Before that, the processor set stock on the entity and the writer saved one object. Now the two
 * numbers live in two tables — shortly in two databases — and a processor cannot write the stock
 * itself: the product it returns has not been saved yet and therefore has no id, and stock is
 * keyed by product id.
 *
 * <p>So the stock level travels with the product to the writer, which runs after the save and has
 * an id to key on. Carrying it in the chunk's own type is what keeps the two beans stateless: the
 * obvious alternative, a map shared between processor and writer, would leak across chunks and
 * would mis-key the moment two rows shared a product name — which this import explicitly allows,
 * because the catalogue has permitted duplicate names since Phase 1.
 *
 * @param product the catalogue row, not yet saved on an insert
 * @param stockQuantity the absolute level the CSV asked for, applied after the product has an id
 */
record ImportedProduct(Product product, int stockQuantity) {}
