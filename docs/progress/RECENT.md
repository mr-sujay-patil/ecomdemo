# Recent Phase Summaries (rolling window: last 2 phases)

> Newest first. When a third summary is added, move the oldest to `docs/progress/archive/phase-XX-summary.md`. Maximum ~30 lines per summary: facts only, no narrative.

<!-- TEMPLATE
## Phase XX: <Title> (tag: phase-XX-complete, PR #N)
**What exists now:** <1–3 lines describing the system after this phase>
**Key code:** <packages and classes that matter next>
**Config & infrastructure:** <profiles, env vars, ports, containers, and how to run>
**Tests:** <new test classes, smoke test additions>
**Gotchas:** <anything surprising the next phase must know>
**Follow-ups (not done, out of scope):** <suggestions deferred to later phases>
-->

## Phase 04: PostgreSQL (tag: pending, PR: pending)
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

## Phase 03: API Documentation (tag: phase-03-complete, PR #3)
**What exists now:** The unchanged Phase 1 API now describes itself. springdoc-openapi builds an
OpenAPI 3 document from the code at startup, served at `/v3/api-docs` (and `.yaml`) and rendered
by Swagger UI at `/swagger-ui.html`. No production logic changed this phase.
**Key code:** `com.ecomdemo.common.OpenApiConfig` holds the document metadata as an `OpenAPI`
bean. Controllers carry `@Tag` (one per feature), `@Operation` and `@ApiResponse`; the DTO records
carry `@Schema` with descriptions and examples; every error response points at `ApiError`.
`OpenApiDocumentationTest` (`@SpringBootTest`, PER_CLASS lifecycle) asserts the document.
**Config & infrastructure:** `springdoc.api-docs.path`, `springdoc.swagger-ui.path`,
`tags-sorter`, `operations-sorter` and `try-it-out-enabled` in `application.properties`. One new
dependency, `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1`, pinned through a
`springdoc.version` property — 3.x is the Boot 4 line, 2.x is Boot 3. It drags Jackson 2 onto the
classpath beside Boot 4's Jackson 3; harmless, but `tools.jackson.databind` is the one to import.
**Tests:** 94 total, was 79 — `OpenApiDocumentationTest` adds 15 (8 plus one per API path). The
smoke test grows from 34 checks to 48.
**Gotchas:** springdoc logs two WARNs at startup saying the docs endpoints are enabled —
advisory, deliberate, and Phase 8 decides access. `@Content` without `mediaType` documents an
error body as `*/*`, so always write `mediaType = "application/json"`. An `@ApiResponse` for a 2xx
does **not** wipe the schema springdoc derives from the return type, as long as it carries no
`@Content` of its own. A `@WebMvcTest` slice cannot see `/v3/api-docs`: it comes from
auto-configuration, so the spec test has to be a `@SpringBootTest`.
**Follow-ups (not done, out of scope):** securing or disabling the docs endpoints — Phase 8.
Splitting the spec into groups per module — Phases 19–20. Generating a client from the YAML —
not planned.
