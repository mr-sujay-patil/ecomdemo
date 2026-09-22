package com.ecomdemo.common;

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
    private static final String SEEDED_NAMES = """
            'Mechanical Keyboard', 'Wireless Mouse', 'Webcam 1080p', '27" 4K Monitor', \
            'Noise-Cancelling Headphones', 'Portable SSD 1TB', 'USB-C Hub', 'Laptop Stand', \
            'Desk Mat', 'Laptop Sleeve 16"'\
            """;

    @Test
    @DisplayName("V1 to V8 are applied, in order, with nothing pending or failed")
    void allMigrationsAreApplied() {
        List<MigrationInfo> applied = List.of(flyway.info().applied());

        assertThat(applied)
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
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
                "outbox events");
    }

    @Test
    @DisplayName("V2 seeded the catalogue exactly once")
    void seedsTheCatalogueOnce() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Counted over the seeded names, not over the whole table. Every @SpringBootTest in the
        // suite shares one context and therefore one H2 database, and several of them create
        // products of their own; a bare count(*) would be asserting on their leftovers as much as
        // on V2. Ten rows across V2's ten names still proves exactly what this test is for: the
        // seed ran, and it ran once.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM product WHERE name IN (" + SEEDED_NAMES + ")",
                        Integer.class))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM product WHERE name = 'Mechanical Keyboard'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("V3 added the category column, backfilled it and indexed it")
    void addsAndBackfillsTheCategoryColumn() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject(
                        "SELECT category FROM product WHERE name = 'Mechanical Keyboard'",
                        String.class))
                .isEqualTo("PERIPHERALS");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM product "
                                + "WHERE category IS NULL AND name IN (" + SEEDED_NAMES + ")",
                        Integer.class))
                .as("every seeded product was backfilled")
                .isZero();

        // The column is nullable on purpose, so that the migration could not break instances of
        // the old application version still inserting products during a deploy.
        assertThat(jdbc.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE upper(table_name) = 'PRODUCT' "
                                + "AND upper(column_name) = 'CATEGORY'",
                        String.class))
                .isEqualTo("YES");

        // INFORMATION_SCHEMA.INDEXES is H2's own view - indexes are not part of the SQL standard
        // information schema, and PostgreSQL exposes them as pg_indexes instead. Phase 7 moves
        // this suite onto PostgreSQL and this one query changes with it.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.indexes "
                                + "WHERE upper(index_name) = 'IDX_PRODUCT_CATEGORY'",
                        Integer.class))
                .as("the index V3 creates alongside the column")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("V4 added product.version and the order_audit table")
    void addsTheVersionColumnAndTheAuditTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // NOT NULL with a default, because the table already had ten rows. A null version would
        // make Hibernate treat an existing product as a new, unsaved entity.
        assertThat(jdbc.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE upper(table_name) = 'PRODUCT' "
                                + "AND upper(column_name) = 'VERSION'",
                        String.class))
                .isEqualTo("NO");
        assertThat(jdbc.queryForObject(
                        "SELECT version FROM product WHERE name = 'Mechanical Keyboard'",
                        Long.class))
                .as("rows that already existed were backfilled with the column default")
                .isZero();

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

    @Test
    @DisplayName("V5 added the users table and seeded exactly one administrator")
    void addsTheUsersTableAndSeedsTheAdmin() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE username = 'admin' AND role = 'ADMIN'",
                        Integer.class))
                .as("the first ADMIN cannot come from the API, so a migration puts it there")
                .isEqualTo(1);

        // The stored value is a BCrypt hash, not a password. The prefix is the algorithm and the
        // cost factor, and the whole string is always 60 characters.
        String stored = jdbc.queryForObject(
                "SELECT password FROM users WHERE username = 'admin'", String.class);
        assertThat(stored)
                .startsWith("$2a$10$")
                .hasSize(60)
                .as("a password must never be stored as typed")
                .isNotEqualTo("admin123");

        // And the hash really is the hash of the documented development password, so the README's
        // "log in as admin/admin123" cannot quietly stop being true.
        assertThat(new BCryptPasswordEncoder().matches("admin123", stored))
                .as("the seeded administrator can actually log in")
                .isTrue();

        // Uniqueness is the database's job: a "is this name free?" check in Java is a read
        // followed by a write, and two registrations can race between the two.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.table_constraints "
                                + "WHERE upper(table_name) = 'USERS' AND constraint_type = 'UNIQUE'",
                        Integer.class))
                .isEqualTo(1);
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

    @Test
    @DisplayName("V8 indexed product.name, which the import looks every row up by")
    void indexesTheProductName() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.indexes "
                                + "WHERE upper(index_name) = 'IDX_PRODUCT_NAME'",
                        Integer.class))
                .isEqualTo(1);
    }

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
