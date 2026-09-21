## Phase 04: PostgreSQL (tag: phase-04-complete, PR #4)
**What exists now:** The application stores its data in a real PostgreSQL 18 server; data survives
a restart. Configuration is split into profiles — `dev` (PostgreSQL, the default) and `test`
(in-memory H2), so `./mvnw clean verify` still needs nothing running. No production Java changed
this phase; it is all dependencies, configuration and the seed script.
**Key code:** Nothing in `src/main/java` changed. `DatasourceConfigurationTest` asserts what each
profile resolves to using `ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`,
without connecting to anything.
**Config & infrastructure:** `application.properties` keeps only what every profile shares and
sets `spring.profiles.active=dev`. `application-dev.properties` (main resources) holds the
PostgreSQL URL as `${POSTGRES_HOST:localhost}` / `PORT` / `DB` / `USER` / `PASSWORD` placeholders
plus the Hikari pool (`EcomdemoPool`, max 10). `application-test.properties` lives in **test**
resources and points at `jdbc:h2:mem:ecomdemo;MODE=PostgreSQL`. `ddl-auto` is `update` for the app
and `create-drop` for tests. Dependencies: `org.postgresql:postgresql` (runtime, version from the
Boot BOM) replaces H2 at runtime; H2 drops to `test` scope; `spring-boot-h2console` is gone. Start
the database with `docker run --name ecomdemo-postgres -e POSTGRES_DB=ecomdemo -e
POSTGRES_USER=ecomdemo -e POSTGRES_PASSWORD=ecomdemo -p 5432:5432 -d postgres:18-alpine`, then
`docker start/stop ecomdemo-postgres` after that.
**Tests:** 102 total, was 94 — `DatasourceConfigurationTest` adds 8. The smoke test grows from 48
checks to 52 on a first run and 54 once a probe from a previous run exists.
**Gotchas:** The `test` profile is activated by surefire's `<systemPropertyVariables>` in
`pom.xml`, **not** by a `src/test/resources/application.properties` — a file of that name shadows
the main one instead of merging with it, so every shared setting would be lost. That same system
property also means a test cannot observe the file's default profile; assert the file instead.
`spring.sql.init.mode` must be `always` for PostgreSQL (`embedded` means in-memory only), which is
why `data.sql` is now guarded by `WHERE NOT EXISTS` as one statement — an unguarded INSERT would
re-seed ten products on every restart. `@DataJpaTest` replaces the datasource with an embedded one
regardless of profile, which is why the repository slices never needed changing. Values written to
`.smoke-state` must be quoted: the probe name contains spaces and the file is read back with `.`.
**Follow-ups (not done, out of scope):** Flyway instead of `ddl-auto=update` — Phase 5.
`@Transactional` and the oversell race — Phase 6. Tests against real PostgreSQL with
Testcontainers — Phase 7. Bringing the database up with the app — Phase 10 (Docker Compose).
