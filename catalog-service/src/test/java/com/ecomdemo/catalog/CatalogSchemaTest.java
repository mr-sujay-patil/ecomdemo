package com.ecomdemo.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.CatalogIntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What {@code catalog_db}'s own migrations actually produced.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>These claims were made in the application's {@code FlywayMigrationTest} until Phase 20c —
 * about its V2 seed, its V3 category backfill and its V4 version column. The migrations still exist
 * in that history and still ran; the TABLE they shaped now lives here, so there was nothing left
 * there to query and the assertions had to come with it.
 *
 * <p>It runs against a real PostgreSQL with Flyway enabled, which is the only way these claims mean
 * anything: the fast suite uses H2 with {@code ddl-auto=create-drop} and never executes a migration,
 * so a broken V1 or a mis-seeded V2 would sail straight through it.
 *
 * <p><strong>The seed is the load-bearing part.</strong> Phase 20b learned this the expensive way:
 * extracting a service moved the {@code product_stock} table and left its CONTENTS behind, and
 * because a missing stock row reads as zero by design, ten products silently showed no stock and
 * nothing failed until the smoke test could not find something to buy. The equivalent mistake here
 * would be a catalogue with no products in it.
 */
@DisplayName("catalog_db schema and seed")
class CatalogSchemaTest extends CatalogIntegrationTest {

    private static final String SEEDED_NAMES = """
            'Mechanical Keyboard', 'Wireless Mouse', 'Webcam 1080p', '27" 4K Monitor', \
            'Noise-Cancelling Headphones', 'Portable SSD 1TB', 'USB-C Hub', 'Laptop Stand', \
            'Desk Mat', 'Laptop Sleeve 16"'\
            """;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("V2 seeded ten products, exactly once")
    void seedsTheCatalogueOnce() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Counted over the seeded NAMES rather than the whole table, because other tests in this
        // module create products of their own and a bare count(*) would assert on their leftovers.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM product WHERE name IN (" + SEEDED_NAMES + ")",
                        Integer.class))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM product WHERE name = 'Mechanical Keyboard'",
                        Integer.class))
                .as("seeded once, not once per restart")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("every seeded product has its category, which the old V3 used to backfill")
    void seedsCategoriesInline() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject(
                        "SELECT category FROM product WHERE name = 'Mechanical Keyboard'",
                        String.class))
                .isEqualTo("PERIPHERALS");

        // The application needed a V3 to add the column and backfill it, because its V2 predated
        // categories. This database's seed writes them inline: a migration that fixes up the one
        // before it is history, not data, and replaying history into a new database would be
        // theatre.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM product "
                                + "WHERE category IS NULL AND name IN (" + SEEDED_NAMES + ")",
                        Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("the optimistic-lock version is NOT NULL and starts at zero")
    void versionColumnIsUsable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // NOT NULL matters: a null version makes Hibernate treat an existing row as a new, unsaved
        // entity, so the first edit of a seeded product would try to INSERT it.
        assertThat(jdbc.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE upper(table_name) = 'PRODUCT' "
                                + "AND upper(column_name) = 'VERSION'",
                        String.class))
                .isEqualTo("NO");
        assertThat(jdbc.queryForObject(
                        "SELECT version FROM product WHERE name = 'Mechanical Keyboard'",
                        Long.class))
                .isZero();
    }

    @Test
    @DisplayName("the seed left the identity sequence past the rows it wrote")
    void identitySequenceWasAdvanced() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // The seed writes its ids EXPLICITLY, because inventory_db's seed is keyed to them - and an
        // explicit id does not advance the identity sequence. Without the setval at the end of V2,
        // the first product created afterwards would be handed id 1 and collide with the keyboard.
        //
        // Asserted by INSERTING one and looking at the id it gets, rather than by reading the
        // sequence's last_value - which needs dynamic SQL, because pg_get_serial_sequence returns a
        // name and not a relation. Inserting also tests the thing that actually matters: not that
        // the sequence holds some number, but that the next product does not collide.
        Long assigned = jdbc.queryForObject(
                "INSERT INTO product (name, description, price) "
                        + "VALUES ('Sequence Probe', 'written by CatalogSchemaTest', 1.00) "
                        + "RETURNING id",
                Long.class);
        try {
            assertThat(assigned)
                    .as("a new product must not be handed an id the seed already used")
                    .isGreaterThan(10L);
        } finally {
            jdbc.update("DELETE FROM product WHERE id = ?", assigned);
        }
    }

    @Test
    @DisplayName("both indexes carried over with the table")
    void indexesExist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'product'", String.class))
                .contains("idx_product_name", "idx_product_category");
    }
}
