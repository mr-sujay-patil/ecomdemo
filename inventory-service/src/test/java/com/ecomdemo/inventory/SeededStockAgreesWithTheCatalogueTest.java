package com.ecomdemo.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two seed migrations live in different services and different databases, and they have to
 * agree. This is what says so.
 *
 * <p><strong>The failure it prevents is a silent one.</strong> Splitting the database split the
 * DDL and left the DATA behind: inventory_db started with an empty {@code product_stock}, so every
 * seeded product reported zero stock. Nothing threw. The catalogue listed all ten products, each
 * showing "0 in stock", and the first thing to notice was the smoke test unable to find a product
 * it could buy two of — twenty checks later and three layers from the cause. A missing stock row
 * reads as zero by design (Phase 20a), which is the right choice and also the reason this cannot
 * fail loudly on its own.
 *
 * <p><strong>Why it is a file comparison rather than a database test.</strong> The claim is about
 * two migrations that will never be applied to the same database, so there is no query that can see
 * both. Reading the SQL is the only place the two facts exist together. It is crude, and it is the
 * honest shape of the coupling: a cross-service invariant that no constraint can hold, checked
 * where it is written down.
 *
 * <p>When the catalogue's seed changes, this test fails and names the product. That is the whole
 * point — the alternative is finding out from a shopper who cannot buy anything.
 */
@DisplayName("Seeded stock")
class SeededStockAgreesWithTheCatalogueTest {

    /** `('Mechanical Keyboard', '...', 8999.00, 25),` — name first, quantity last. */
    private static final Pattern CATALOGUE_ROW =
            Pattern.compile("\\(\\s*'((?:[^']|'')*)'\\s*,.*?,\\s*[0-9.]+\\s*,\\s*(\\d+)\\s*\\)");

    /** `( 1, 25, 0),   -- Mechanical Keyboard` — quantity second, name in the trailing comment. */
    private static final Pattern STOCK_ROW =
            Pattern.compile("\\(\\s*\\d+\\s*,\\s*(\\d+)\\s*,\\s*\\d+\\s*\\)\\s*[,;]\\s*--\\s*(.+)");

    @Test
    @DisplayName("every seeded product has the quantity the catalogue's own seed gives it")
    void agreesWithTheCataloguesSeed() throws IOException {
        List<String[]> catalogue = extract(
                "ecomdemo-app/src/main/resources/db/migration/V2__seed_products.sql",
                CATALOGUE_ROW, 1, 2);
        List<String[]> stock = extract(
                "inventory-service/src/main/resources/db/migration/V2__seed_stock_for_the_seeded_catalogue.sql",
                STOCK_ROW, 2, 1);

        assertThat(catalogue)
                .as("the catalogue's seed should still contain ten products; if it does not, this "
                        + "test's assumption about the file has broken, not the data")
                .hasSize(10);

        assertThat(stock)
                .as("inventory's seed must cover exactly the products the catalogue seeds, in the "
                        + "same order — the ids are positional")
                .containsExactlyElementsOf(catalogue);
    }

    /** Pulls (name, quantity) pairs out of one migration, in file order. */
    private static List<String[]> extract(String path, Pattern pattern, int nameGroup, int quantityGroup)
            throws IOException {
        String sql = Files.readString(ProjectRoot.resolve(path));
        Matcher matcher = pattern.matcher(sql);
        List<String[]> rows = new ArrayList<>();
        while (matcher.find()) {
            rows.add(new String[] {matcher.group(nameGroup).trim(), matcher.group(quantityGroup)});
        }
        return rows;
    }
}
