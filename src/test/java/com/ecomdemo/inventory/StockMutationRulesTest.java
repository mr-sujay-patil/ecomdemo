package com.ecomdemo.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.ecomdemo.catalog.Product;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary the type system cannot hold.
 *
 * <p>{@code catalog} and {@code inventory} share one entity: {@link Product} owns the
 * {@code stock_quantity} column, and its {@code reduceStock} and {@code setStockQuantity} are
 * public methods on a type every module is allowed to see. So nothing in Java stops {@code order},
 * {@code cart} or {@code batch} from changing stock directly — and before this phase,
 * {@code order} and {@code batch} both did.
 *
 * <p>{@code ModularityTest} cannot catch it either. Modulith checks which PACKAGES reach into
 * which; this is one exposed type being used two different ways by two different modules, which is
 * legal at every level Modulith inspects.
 *
 * <p>So the rule is written out. It is the honest price of the decision recorded in
 * {@code InventoryService} and in {@code docs/decisions.md}: the behaviour was separated and the
 * table was not, and this test is what makes that separation real rather than aspirational. A
 * boundary a test enforces is weaker than one the compiler enforces and enormously stronger than
 * one a comment requests — and if Phase 20 does split the table, this test is the list of every
 * place that has to change.
 *
 * <p>Verified by mutation while it was written: putting {@code product.reduceStock(...)} back into
 * {@code OrderPlacementService} fails it, which is the only way to know a rule like this is
 * actually wired to anything.
 *
 * <h2>Why {@code catalog} is exempt, stated plainly rather than quietly</h2>
 *
 * <p>The rule permits two modules, not one: {@code inventory} and {@code catalog}. The admin
 * endpoint {@code PUT /api/products/{id}} replaces a product wholesale, stock included, and that
 * is {@code ProductService.update}.
 *
 * <p>It cannot be routed through {@code InventoryService}, and the reason is structural rather
 * than lazy. {@code inventory} already depends on {@code catalog} — it holds a {@link Product} and
 * saves through {@code ProductService} — so {@code catalog} calling back into {@code inventory}
 * would form precisely the dependency cycle this phase spent its first commit removing.
 * {@code ModularityTest} would fail, and rightly.
 *
 * <p>So this is the COST of the decision recorded in {@code docs/decisions.md} — separate the
 * behaviour, leave the table alone — showing up where costs usually show up, one layer down from
 * where the decision was made. Two modules sharing an entity means the module that owns the entity
 * keeps access to all of it; no arrangement of tests changes that. Splitting {@code product} and
 * {@code product_stock} is what would let the rule name a single module, and Phase 20 is where
 * that question belongs.
 *
 * <p>What the rule still buys is the part that matters: {@code order}, {@code cart} and
 * {@code batch} — the CONSUMERS — cannot touch stock. Before this phase, {@code order} and
 * {@code batch} both did.
 */
@DisplayName("Stock mutation rules")
class StockMutationRulesTest {

    /**
     * Production classes only. {@code DO_NOT_INCLUDE_TESTS} matters here: tests legitimately build
     * products with arbitrary stock to set up a scenario, and a rule that forbade that would be
     * answered by weakening the rule rather than by fixing anything.
     */
    private static final JavaClasses PRODUCTION_CODE =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.ecomdemo");

    @Test
    @DisplayName("no module outside catalog and inventory may take stock out")
    void onlyInventoryReducesStock() {
        noClasses()
                .that()
                .resideOutsideOfPackages("com.ecomdemo.inventory..", "com.ecomdemo.catalog..")
                .should()
                .callMethod(Product.class, "reduceStock", int.class)
                .because(
                        "taking stock out is the inventory module's job; going around it skips the "
                                + "availability check, the InsufficientStockException a shopper should "
                                + "see, and the cache eviction that follows the save")
                .check(PRODUCTION_CODE);
    }

    @Test
    @DisplayName("no module outside catalog and inventory may set a stock level")
    void onlyInventorySetsStock() {
        // The CSV import used to call setStockQuantity directly; it now goes through
        // InventoryService.setStockLevel. That method is thin to the point of looking pointless -
        // a setter with a bounds check - and this test is the entire reason it exists.
        //
        // catalog is exempt because ProductService.update is the admin's full replace; see the
        // class comment for why that cannot be routed through inventory without a cycle.
        noClasses()
                .that()
                .resideOutsideOfPackages("com.ecomdemo.inventory..", "com.ecomdemo.catalog..")
                .should()
                .callMethod(Product.class, "setStockQuantity", int.class)
                .because(
                        "every write to stock from a consumer module goes through InventoryService, "
                                + "so 'what can change this number?' is answered by the two modules "
                                + "that share the entity rather than by anything that imports it")
                .check(PRODUCTION_CODE);
    }

    @Test
    @DisplayName("reading stock stays open to everyone")
    void readingStockIsNotRestricted() {
        // Stated as a test so the intent is not mistaken for an oversight. Availability is a fact
        // about the catalogue that any module may read - the product listing shows it, the cart
        // checks it, the smoke test asserts on it. It is WRITING that needs an owner. A rule that
        // locked reads down too would push callers into working around it, which is how boundaries
        // acquire the reputation of being obstacles.
        Product product = new Product("Rule Lamp", "a lamp", new java.math.BigDecimal("1.00"), 5);

        org.assertj.core.api.Assertions.assertThat(product.hasStockFor(5)).isTrue();
        org.assertj.core.api.Assertions.assertThat(product.getStockQuantity()).isEqualTo(5);
    }
}
