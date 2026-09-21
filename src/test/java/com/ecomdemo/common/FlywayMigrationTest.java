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

/**
 * Proves the "Done when" of Phase 5: the database is built entirely by Flyway, and Hibernate's
 * validation of it passes.
 *
 * <p>The proof is mostly the fact that this context starts at all. {@code ddl-auto=validate}
 * means Hibernate creates nothing and fails the startup if a single table, column, type or
 * nullability does not match the entities - so if the migrations were wrong, or had not run,
 * every {@code @SpringBootTest} in the suite would fail to load. The assertions below add the
 * detail: which migrations ran, that none is pending or failed, and that V3's column and index
 * are really there.
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

    @Test
    @DisplayName("V1 to V3 are applied, in order, with nothing pending or failed")
    void allMigrationsAreApplied() {
        List<MigrationInfo> applied = List.of(flyway.info().applied());

        assertThat(applied)
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3");
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
                "init schema", "seed products", "add product category");
    }

    @Test
    @DisplayName("V2 seeded the catalogue exactly once")
    void seedsTheCatalogueOnce() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Integer.class))
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
                        "SELECT count(*) FROM product WHERE category IS NULL", Integer.class))
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
}
