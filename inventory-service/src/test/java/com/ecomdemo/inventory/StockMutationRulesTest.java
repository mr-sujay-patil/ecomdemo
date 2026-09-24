package com.ecomdemo.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may change how much of a product there is — now answered by ONE module.
 *
 * <h2>What this test used to have to say, and no longer does</h2>
 *
 * <p>In Phase 19 this rule had to permit <strong>two</strong> modules, {@code inventory} and
 * {@code catalog}, and the test said so at length and with some embarrassment. Stock was a column
 * on {@code Product}, {@code catalog} owned that entity, and an entity's owner cannot be denied
 * access to one of its own columns. The admin endpoint {@code PUT /api/products/{id}} wrote stock
 * directly, and routing it through {@code InventoryService} would have formed a cycle — because
 * {@code inventory} depended on {@code catalog} to save the product it had just mutated.
 *
 * <p>That test closed by naming the condition under which it could be tightened:
 *
 * <blockquote>Splitting {@code product} and {@code product_stock} is what would let the rule name a
 * single module, and Phase 20 is where that question belongs.</blockquote>
 *
 * <p>Phase 20 split the table, and this is that tightening. The rule now names {@code inventory}
 * and nothing else. The exemption is gone, along with the paragraph explaining it.
 *
 * <h2>Why the split also reversed a dependency</h2>
 *
 * <p>The cycle that forced the old exemption disappeared for the same reason the exemption did.
 * {@code InventoryService} deals in product <em>ids</em> now — it never holds a {@code Product},
 * never saves through the catalogue, and does not know what a catalogue is. So {@code catalog} can
 * call it freely, which is what {@code ProductService} does to put a quantity in a
 * {@code ProductResponse}.
 *
 * <p>That is the shape the services must have: an inventory-service will not have a catalogue to
 * depend on. Splitting the table is what made the code admit it.
 *
 * <h2>Phase 20b made the old rule VACUOUS, and ArchUnit said so</h2>
 *
 * <p>This used to read "no class outside {@code com.ecomdemo.inventory} may call
 * {@code reduce}". Moved into inventory-service, that rule matched nothing at all — the other
 * modules are not on this classpath, so there are no outside classes left to forbid — and ArchUnit
 * failed it rather than passing an empty check, which is exactly the right behaviour. A rule that
 * silently checks nothing is worse than no rule, because it reports success.
 *
 * <p>The fix is not {@code allowEmptyShould(true)}. It is to assert what is still true. The
 * boundary the old rule protected has moved from a TEST to a PROCESS: no amount of carelessness in
 * order-service can touch {@code ProductStock} now, because the class is not there to touch. That
 * is a stronger guarantee than this test ever gave, and it needs no test.
 *
 * <p>What a test can still add is the boundary INSIDE this service: that the only class here which
 * mutates stock is {@code InventoryService}. That is the thing a hurried edit breaks — a second
 * component reaching for the repository — and it is what the rules below now say.
 *
 * <h2>Why this is still a test and not a compiler guarantee</h2>
 *
 * <p>{@link ProductStock}'s mutators are package-private, so within one deployable the compiler
 * already does most of this work. The rule remains because package-private is a property of the
 * <em>package</em>, not of the module: anything else placed in {@code com.ecomdemo.inventory}
 * would have the same access, and "do not add a class here that writes stock behind the service"
 * is exactly the sort of thing a hurried edit does. It costs one test to say it out loud.
 */
@DisplayName("Stock mutation rules")
class StockMutationRulesTest {

    /**
     * Production classes only. {@code DO_NOT_INCLUDE_TESTS} matters here: tests legitimately build
     * stock rows with arbitrary quantities to set up a scenario, and a rule that forbade that
     * would be answered by weakening the rule rather than by fixing anything.
     */
    private static final JavaClasses PRODUCTION_CODE =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.ecomdemo.inventory");

    @Test
    @DisplayName("only InventoryService may take stock out")
    void onlyInventoryReducesStock() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(InventoryService.class.getName())
                .should()
                .callMethod(ProductStock.class, "reduce", int.class)
                .because(
                        "taking stock out is InventoryService's job; going around it skips the "
                                + "availability check, the InsufficientStockException a shopper should "
                                + "see, and the stock-changed event the catalogue cache needs")
                .check(PRODUCTION_CODE);
    }

    @Test
    @DisplayName("only InventoryService may set a stock level")
    void onlyInventorySetsStock() {
        // Both remaining callers - the catalogue's create/update and the CSV import - are in
        // another process entirely now and reach this service over HTTP, so the only classes this
        // rule can see are the ones deployed beside InventoryService.
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(InventoryService.class.getName())
                .should()
                .callMethod(ProductStock.class, "setQuantity", int.class)
                .because(
                        "every write to stock goes through InventoryService, so 'what can change "
                                + "this number?' has exactly one answer inside this service, and "
                                + "exactly one HTTP endpoint outside it")
                .check(PRODUCTION_CODE);
    }

    @Test
    @DisplayName("reading stock stays open to everyone, through the service")
    void readingStockIsNotRestricted() {
        // Stated as a test so the intent is not mistaken for an oversight. Availability is a fact
        // any module may read - the product listing shows it, the cart checks it, the smoke test
        // asserts on it. It is WRITING that needs an owner. A rule that locked reads down too
        // would push callers into working around it, which is how boundaries acquire the
        // reputation of being obstacles.
        ProductStock stock = new ProductStock(1L, 5);

        org.assertj.core.api.Assertions.assertThat(stock.has(5)).isTrue();
        org.assertj.core.api.Assertions.assertThat(stock.getQuantity()).isEqualTo(5);
    }
}
