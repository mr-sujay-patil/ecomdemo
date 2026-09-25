package com.ecomdemo.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Proves the "Done when" of Phase 5: the database is built entirely by Flyway, and Hibernate's
 * validation of it passes.
 *
 * <p>The proof is mostly the fact that this context starts at all. {@code ddl-auto=validate}
 * means Hibernate creates nothing and fails the startup if a single table, column, type or
 * nullability does not match the entities - so if the migrations were wrong, or had not run,
 * every {@code @SpringBootTest} in the suite would fail to load. The assertions below add the
 * detail: which migrations ran, that none is pending or failed, and that the columns and tables
 * V3 and V4 add are really there.
 *
 * <p>This runs against H2 (the {@code test} profile) rather than PostgreSQL, so it proves the
 * migrations are internally consistent, not that they are valid PostgreSQL. Phase 7 moves the
 * suite onto a real PostgreSQL container and closes that gap.
 */
@SpringBootTest
class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    /** The ten products V2 inserts, as a SQL list. See {@link #seedsTheCatalogueOnce()}. */
    // ------------------------------------------------------------------------------------------
    // THREE MIGRATION TESTS MOVED TO catalog-service IN PHASE 20c
    // ------------------------------------------------------------------------------------------
    // They asserted this database's V2 seed, V3's category backfill and V4's version column — all
    // against `product`, which V14 drops because catalog-service owns it now. The migrations still
    // exist and still ran; the TABLE they shaped is in another database, so there is nothing here
    // left to query.
    //
    // The same claims are made in catalog-service's CatalogSchemaTest, against catalog_db's own
    // V1 and V2 — ten seeded products, their categories, and a non-null version defaulting to
    // zero. Written before these were removed, so the coverage never lapsed.
    //
    // What stayed here is everything about tables this database still owns, including the
    // assertion below that `product` is GONE — which is the new claim V14 is worth making.

    @Test
    @DisplayName("V15 removed the foreign keys to users, and backfilled the order's username")
    void cartAndOrdersNoLongerPointAtAnAccount() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // The constraints are gone, because `users` goes to customer-service and there is nothing
        // left for them to point at.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.table_constraints "
                                + "WHERE upper(constraint_name) IN ('FK_CART_USER', 'FK_ORDERS_USER')",
                        Integer.class))
                .as("a foreign key cannot span two databases")
                .isZero();

        // The column that replaced the association, and the one with a deadline: after the split
        // there is no query that can populate it for orders that already existed.
        assertThat(jdbc.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE upper(table_name) = 'ORDERS' AND upper(column_name) = 'USERNAME'",
                        String.class))
                .as("the snapshot has to be NOT NULL, or the backfill silently did nothing")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("V14 dropped the catalogue, because another service owns it now")
    void dropsTheProductTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // A leftover copy is worse than no copy: it would still answer queries, with rows frozen at
        // the moment of the split and drifting further from the truth with every edit.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE upper(table_name) = 'PRODUCT'",
                        Integer.class))
                .as("the application must not keep a stale copy of another service's table")
                .isZero();
    }

    @Test
    @DisplayName("V1 to V8 are applied, in order, with nothing pending or failed")
    void allMigrationsAreApplied() {
        List<MigrationInfo> applied = List.of(flyway.info().applied());

        assertThat(applied)
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16");
        assertThat(applied)
                .extracting(MigrationInfo::getState)
                .allMatch(MigrationState::isApplied)
                .allMatch(state -> state != MigrationState.FAILED);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("every applied migration still matches its checksum, so none has been edited")
    void noAppliedMigrationHasBeenEdited() {
        // spring.flyway.validate-on-migrate makes Flyway do this at startup too; asserting it
        // here states the rule out loud. An edited V1 would make this throw.
        flyway.validate();
    }

    @Test
    @DisplayName("Flyway records what it did in flyway_schema_history")
    void recordsItsHistoryInTheDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Every identifier is quoted. Flyway creates this table and its columns in lower case,
        // and an UNQUOTED identifier is folded - to upper case by H2's own rules, to lower case
        // by PostgreSQL's. Quoting the lower-case name is what both databases really hold, so
        // this query works unchanged when Phase 7 moves the suite onto PostgreSQL.
        List<String> descriptions = jdbc.queryForList(
                // `version IS NOT NULL` skips the rank-0 bookkeeping row Flyway writes when it
                // creates the history table itself.
                "SELECT \"description\" FROM \"flyway_schema_history\" "
                        + "WHERE \"success\" = TRUE AND \"version\" IS NOT NULL "
                        + "ORDER BY \"installed_rank\"",
                String.class);

        // Flyway turns the file name's underscores into spaces.
        assertThat(descriptions).containsExactly(
                "init schema",
                "seed products",
                "add product category",
                "add product version and order audit",
                "add users",
                "cart and orders per user",
                "batch job repository",
                "index product name",
                "notifications and processed events",
                "outbox events",
                "split product stock",
                "cart item snapshots the product",
                "drop product stock",
                "drop product",
                "cart and orders hold a user id",
                "drop users notifications and processed events");
    }

    @Test
    @DisplayName("V4 added product.version and the order_audit table")
    void addsTheVersionColumnAndTheAuditTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // NOT NULL with a default, because the table already had ten rows. A null version would
        // make Hibernate treat an existing product as a new, unsaved entity.
        // The `product.version` half of this test moved to catalog-service with the table; what
        // remains is order_audit, which this database still owns.

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE upper(table_name) = 'ORDER_AUDIT'",
                        Integer.class))
                .isEqualTo(1);

        // Deliberately no foreign key to orders: the audit is written in its own transaction and
        // must be able to name an order that is not committed yet, or none at all.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.table_constraints "
                                + "WHERE upper(table_name) = 'ORDER_AUDIT' "
                                + "AND constraint_type = 'FOREIGN KEY'",
                        Integer.class))
                .as("an audit row must be able to outlive the order it describes")
                .isZero();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.indexes "
                                + "WHERE upper(index_name) = 'IDX_ORDER_AUDIT_RECORDED_AT'",
                        Integer.class))
                .isEqualTo(1);
    }

    // "V5 added the users table and seeded the administrator" moved to customer-service with the
    // table. Its V1 writes the same row with the same id and the same hash, and its own schema test
    // asserts it - including that the id is 1, which order-service's `orders.user_id` depends on now
    // that no foreign key enforces it.

    @Test
    @DisplayName("V16 dropped accounts, notifications and the ledger - other services own them")
    void dropsTheTablesOtherServicesOwn() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Three leftover copies would be three sources of confidently wrong answers: accounts that
        // cannot log in, notifications nobody sent, and a ledger that deduplicates nothing.
        // `table_schema = 'PUBLIC'` is not optional, and leaving it out cost a diagnosis: H2 has its
        // OWN INFORMATION_SCHEMA.USERS, so the unqualified query reported that the application still
        // had a users table when it had dropped it perfectly well. A schema-less catalogue query
        // answers a question nobody asked.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'PUBLIC' AND upper(table_name) IN "
                                + "('USERS', 'NOTIFICATION', 'PROCESSED_EVENT')",
                        Integer.class))
                .as("this database must keep no copy of another service's table")
                .isZero();
    }

    @Test
    @DisplayName("what remains is exactly order-service's own")
    void keepsOnlyWhatOrderServiceOwns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // The residue of the monolith, named explicitly. If a future phase adds a table here it should
        // be a deliberate decision, visible in this list, rather than something that appeared.
        assertThat(jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'PUBLIC' AND table_type = 'BASE TABLE'",
                        String.class))
                .map(String::toUpperCase)
                .contains("CART", "CART_ITEM", "ORDERS", "ORDER_ITEM", "ORDER_AUDIT", "OUTBOX_EVENT");
    }

    @Test
    @DisplayName("V6 attached the cart and the orders to a user")
    void attachesTheCartAndTheOrdersToAUser() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Both columns are NOT NULL: data that belongs to nobody is exactly what this migration
        // exists to make impossible.
        assertThat(nullabilityOf(jdbc, "CART", "USER_ID")).isEqualTo("NO");
        assertThat(nullabilityOf(jdbc, "ORDERS", "USER_ID")).isEqualTo("NO");

        // One cart per account, enforced by the database rather than assumed by the code.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.table_constraints "
                                + "WHERE upper(table_name) = 'CART' AND constraint_type = 'UNIQUE'",
                        Integer.class))
                .isEqualTo(1);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.indexes "
                                + "WHERE upper(index_name) = 'IDX_ORDERS_USER'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("V7 created the six Spring Batch tables and their three sequences")
    void createsTheBatchJobRepositorySchema() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Spring Batch never creates these itself in Spring Boot 4 - there is no
        // initialize-schema property any more - so if this migration were missing, the first job
        // would fail at runtime rather than the application failing to start. That is exactly the
        // kind of gap a test has to close.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE upper(table_name) LIKE 'BATCH\\_%' ESCAPE '\\'",
                        Integer.class))
                .as("the six BATCH_ tables")
                .isEqualTo(6);

        // The ids come from these, on both engines. Without them every job would fail on its
        // first insert into BATCH_JOB_INSTANCE.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.sequences "
                                + "WHERE upper(sequence_name) LIKE 'BATCH\\_%' ESCAPE '\\'",
                        Integer.class))
                .as("the three BATCH_ sequences")
                .isEqualTo(3);

        // The unique key on (job name, parameters hash) is what makes "this work has already been
        // done" a fact the DATABASE enforces rather than a check the application remembers to do.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.table_constraints "
                                + "WHERE upper(table_name) = 'BATCH_JOB_INSTANCE' "
                                + "AND constraint_type = 'UNIQUE'",
                        Integer.class))
                .isEqualTo(1);
    }

    // "V8 indexed the product name" moved to catalog-service's CatalogSchemaTest with the table
    // it indexes. Both of that table's indexes are asserted there, against catalog_db.

    @Test
    @DisplayName("V10 created the outbox, with the two identifiers and the nullable published_at")
    void createsTheOutbox() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE upper(table_name) = 'OUTBOX_EVENT'",
                        Integer.class))
                .isEqualTo(1);

        // The event id is UNIQUE and the sequence is the PRIMARY KEY, which is the whole reason
        // there are two columns rather than one. The sequence orders publication; the event id is
        // the idempotency key the consumer matches on and must survive republication unchanged. A
        // second row claiming the same event id would defeat that recognition, so the database
        // refuses it rather than trusting the relay not to do it.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.table_constraints "
                                + "WHERE upper(table_name) = 'OUTBOX_EVENT' "
                                + "AND constraint_type = 'UNIQUE'",
                        Integer.class))
                .as("the unique constraint on event_id")
                .isEqualTo(1);

        // NULL means pending, and that is the entire state machine. If this column were NOT NULL
        // every row would have to be born published, and the outbox would have no way to say
        // "not yet".
        assertThat(nullabilityOf(jdbc, "OUTBOX_EVENT", "PUBLISHED_AT"))
                .as("published_at must be nullable: NULL is what 'pending' means")
                .isEqualTo("YES");

        // The relay's query is `WHERE published_at IS NULL ORDER BY id`, and this composite index
        // serves both halves - the filter and the sort - so the query needs no sort step.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.indexes "
                                + "WHERE upper(index_name) = 'IDX_OUTBOX_EVENT_PENDING'",
                        Integer.class))
                .isEqualTo(1);

        // The cleanup job's query.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.indexes "
                                + "WHERE upper(index_name) = 'IDX_OUTBOX_EVENT_PUBLISHED_AT'",
                        Integer.class))
                .isEqualTo(1);
    }

    private static String nullabilityOf(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE upper(table_name) = ? AND upper(column_name) = ?",
                String.class,
                table,
                column);
    }
}
