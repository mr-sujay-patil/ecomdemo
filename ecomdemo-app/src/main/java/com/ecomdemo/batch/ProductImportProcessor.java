package com.ecomdemo.batch;

import com.ecomdemo.clients.catalog.ProductUpsert;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * The middle third of the chunk: validate a row, then turn it into the {@link Product} the writer
 * will save.
 *
 * <h2>Validate</h2>
 *
 * <p>Every rule below has a message naming the column and the offending value, because these
 * messages end up in the error file and the person reading it has the file open, not the code.
 * A rule that fails throws {@link InvalidProductRowException} — the one type the step is willing
 * to skip.
 *
 * <h2>Upsert</h2>
 *
 * <p>A catalogue feed is not a list of new products; it is the supplier's current view of the
 * whole catalogue, most of which is already here. So the processor looks the product up by name
 * and either updates it or creates it. Two consequences are worth naming:
 *
 * <ul>
 *   <li><strong>The import is idempotent.</strong> Running the same file twice leaves the
 *       catalogue in the same state as running it once — which is precisely what makes a restart
 *       safe, because a restart re-reads the rows of the chunk that was rolled back.
 *   <li><strong>The key is the product name, and the schema does not enforce that it is
 *       unique.</strong> Where several products share a name the oldest one wins. A real
 *       catalogue import would key on a supplier SKU held in a unique column; adding one is a
 *       schema change this phase does not own, so the limitation is written down here and in
 *       {@code docs/decisions.md} rather than hidden.
 * </ul>
 *
 * <p>Returning {@code null} from an {@code ItemProcessor} FILTERS the item — the writer never
 * sees it and Spring Batch counts it as filtered rather than skipped. This processor never does
 * that: a row is either good or rejected with a reason, and silently dropping rows is how an
 * import comes to disagree with its source without anybody noticing.
 */
@Component
class ProductImportProcessor implements ItemProcessor<ProductCsvRow, ImportedProduct> {

    private static final int MAX_NAME = 255;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_CATEGORY = 50;
    private static final int MAX_PRICE_SCALE = 2;

    /**
     * The import sets stock, so since Phase 19 it goes through the inventory module like every
     * other write to that number. The alternative - calling {@code setStockQuantity} directly -
     * would be a hole in the boundary exactly wide enough to make the boundary meaningless.
     */
    ProductImportProcessor() {
    }

    @Override
    public ImportedProduct process(ProductCsvRow row) {
        String name = trimToNull(row.name());
        if (name == null) {
            throw new InvalidProductRowException(row, "name is required");
        }
        if (name.length() > MAX_NAME) {
            throw new InvalidProductRowException(
                    row, "name is longer than %d characters".formatted(MAX_NAME));
        }

        String description = trimToNull(row.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new InvalidProductRowException(
                    row, "description is longer than %d characters".formatted(MAX_DESCRIPTION));
        }

        String category = trimToNull(row.category());
        if (category != null && category.length() > MAX_CATEGORY) {
            throw new InvalidProductRowException(
                    row, "category is longer than %d characters".formatted(MAX_CATEGORY));
        }

        BigDecimal price = price(row);
        int stock = stockQuantity(row);

        // THE LOOKUP MOVED TO THE OTHER SIDE. This used to find an existing product by name and
        // mutate it, which worked because the result was a managed entity inside the chunk's
        // transaction. Across a network that would be one HTTP call per ROW just to learn an id -
        // an N+1 over the wire, on the one job built to handle ten thousand of them.
        //
        // So the row travels as a value with a null id, and catalog-service resolves the name
        // when it writes the chunk. Same rule, same "oldest first" behaviour, one round trip.
        ProductUpsert product = new ProductUpsert(null, name, description, price, category);

        // Stock is NOT set here, and the reason is the whole of Phase 20 in one line: it lives in
        // another table now, and shortly in another database, so it is not a field on this object.
        // It travels to the writer, which runs after the save and has an id to key on.
        return new ImportedProduct(product, stock);
    }

    private BigDecimal price(ProductCsvRow row) {
        String raw = trimToNull(row.price());
        if (raw == null) {
            throw new InvalidProductRowException(row, "price is required");
        }
        BigDecimal price;
        try {
            price = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new InvalidProductRowException(row, "price '%s' is not a number".formatted(raw));
        }
        if (price.signum() <= 0) {
            throw new InvalidProductRowException(row, "price '%s' must be positive".formatted(raw));
        }
        // The column is DECIMAL(12,2). Three decimal places would be rounded away on the way in,
        // which is a silent change to somebody's price - so it is a rejection, not a rounding.
        if (price.scale() > MAX_PRICE_SCALE) {
            throw new InvalidProductRowException(
                    row, "price '%s' has more than %d decimal places".formatted(raw, MAX_PRICE_SCALE));
        }
        return price.setScale(MAX_PRICE_SCALE);
    }

    private int stockQuantity(ProductCsvRow row) {
        String raw = trimToNull(row.stockQuantity());
        if (raw == null) {
            throw new InvalidProductRowException(row, "stock_quantity is required");
        }
        int stock;
        try {
            stock = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new InvalidProductRowException(
                    row, "stock_quantity '%s' is not a whole number".formatted(raw));
        }
        if (stock < 0) {
            throw new InvalidProductRowException(
                    row, "stock_quantity '%s' must not be negative".formatted(raw));
        }
        return stock;
    }

    private static String trimToNull(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
    }
}
