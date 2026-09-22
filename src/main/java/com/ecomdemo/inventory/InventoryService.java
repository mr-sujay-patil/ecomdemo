package com.ecomdemo.inventory;

import com.ecomdemo.catalog.Product;
import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.shared.InsufficientStockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where every change to how much of a product exists goes through.
 *
 * <h2>Why this is a module and not three methods on {@code ProductService}</h2>
 *
 * <p>A catalogue and an inventory answer different questions about the same row. The catalogue
 * describes a product — what it is called, what it costs, what it is — and that changes when
 * somebody edits it, which is rarely, deliberately, and by a human. The inventory counts it, and
 * that changes on every sale, concurrently, under contention, with an optimistic lock and a
 * rollback path. Same table, two very different rates of change and two very different
 * correctness stories. That divergence is what a bounded context <em>is</em>, and noticing it
 * before the split into services is the entire reason this phase comes before Phase 20.
 *
 * <p>It is also the boundary that will be load-bearing later. When the catalogue becomes a
 * service, "how many are left" is the question that cannot be answered from a cached copy, and
 * the code that asks it is already gathered in one place.
 *
 * <h2>What was deliberately NOT done</h2>
 *
 * <p>The table was not split. {@code stock_quantity} is still a column on {@code product}, and
 * {@link Product} is still one entity owned by the catalogue. Splitting it into a
 * {@code product_stock} table is the textbook answer and would have been the wrong change to make
 * here: it would have touched the optimistic-locking path from Phase 12, the cache eviction from
 * Phase 16 and the CSV import, in a phase whose smoke test gains no new checks to catch what
 * broke. This phase separates the BEHAVIOUR; the schema follows in Phase 20 if the split into
 * services actually demands it. Recorded in {@code docs/decisions.md} rather than left implicit.
 *
 * <p>The cost of that choice is honest, and worth being precise about. {@code Product.reduceStock}
 * is a public method on an exposed type, so nothing in the <em>type system</em> stops another
 * module calling it — {@code StockMutationRulesTest} is the ArchUnit rule that does. And the rule
 * permits two modules rather than one: {@code catalog} keeps access because it owns the entity and
 * because {@code ProductService.update} is the admin's full replace, which cannot call into here
 * without forming a cycle back through the dependency this class already has on it. Two modules
 * sharing an entity means the owner keeps access to all of it; that is a property of the table not
 * being split, not something a test can arrange away.
 *
 * <p>What the rule does buy is the part that was actually going wrong: {@code order} and
 * {@code batch} both mutated stock directly before this phase, and now neither can.
 */
@Service
public class InventoryService {

    private final ProductService catalogue;

    public InventoryService(ProductService catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * Checks that a product can cover a quantity, and says so in the customer's terms if not.
     *
     * <p>Read-only, and separate from {@link #reserve} on purpose: checkout validates every line
     * before writing any of them, so that a cart of five items fails with "3 requested, 2
     * available" for the line that is actually short rather than for whichever line happened to
     * be written when the transaction gave up.
     */
    public void requireAvailable(Product product, int quantity) {
        if (!product.hasStockFor(quantity)) {
            throw new InsufficientStockException(
                    product.getName(), quantity, product.getStockQuantity());
        }
    }

    /**
     * Takes {@code quantity} out of stock.
     *
     * <p><strong>{@code MANDATORY}, for the reason Phase 18's {@code OutboxWriter} uses it.</strong>
     * A reservation is only meaningful as part of the transaction that creates the order. If this
     * were allowed to open one of its own, stock could commit while the order that justified it
     * rolled back — goods sold to nobody, and no error anywhere. {@code REQUIRED}, the default,
     * would do exactly that silently. {@code MANDATORY} refuses to run outside a transaction, so
     * the requirement is enforced rather than assumed.
     *
     * <p>The availability check is repeated here even though {@link #requireAvailable} has usually
     * just run. It is cheap, and it means this method is safe for any caller rather than only for
     * one that remembered the protocol — {@code Product.reduceStock} would otherwise throw
     * {@code IllegalArgumentException}, which is an internal error rather than the 409 a shopper
     * should see.
     *
     * <p>Saving through the catalogue rather than a repository of our own is what keeps the cache
     * correct: {@code ProductService.save} publishes {@code ProductStockChangedEvent}, and the
     * Phase 16 evictor acts on it after the commit. An inventory module that wrote directly to
     * the repository would have silently reintroduced the stale-catalogue defect that phase fixed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Product product, int quantity) {
        requireAvailable(product, quantity);
        product.reduceStock(quantity);
        catalogue.save(product);
    }

    /**
     * Sets an absolute stock level, as a catalogue feed does.
     *
     * <p>Thin, and thin on purpose. It carries no logic the caller could not write itself; what it
     * carries is the RULE — every write to stock goes through this module, so "what can change
     * this number?" has one answer and {@code StockMutationRulesTest} can enforce it. A setter the
     * CSV import called directly would be a hole in the boundary exactly wide enough to make the
     * boundary meaningless.
     *
     * <p>It does not save. The import runs inside a Spring Batch chunk whose writer does the
     * saving, and the entity is managed, so an extra save here would be a second write per row and
     * would publish a cache eviction per imported product on top of it.
     */
    public void setStockLevel(Product product, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException(
                    "Stock level cannot be negative: " + quantity + " for '" + product.getName() + "'");
        }
        product.setStockQuantity(quantity);
    }
}
