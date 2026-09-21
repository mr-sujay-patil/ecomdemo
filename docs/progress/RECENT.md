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

## Phase 03: API Documentation (tag: pending, PR: pending)
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

## Phase 02: Automated Testing (tag: phase-02-complete, PR #2)
**What exists now:** A four-level test suite over the unchanged Phase 1 code: 79 tests, 0
skipped, ~9s. No production code changed this phase apart from reverting a stray `server.port`.
**Key code:** `com.ecomdemo.support.TestData` builds entities and sets generated ids
reflectively — use it rather than adding setters. `{Product,Cart,Order}ServiceTest` (Mockito),
`{Product,Cart,Order}ControllerTest` (`@WebMvcTest` + `@MockitoBean` + `MockMvcTester`),
`{Cart,Order}RepositoryTest` (`@DataJpaTest` + `TestEntityManager`).
**Config & infrastructure:** Boot 4 moved the slice annotations —
`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`,
`org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`,
`org.springframework.boot.jpa.test.autoconfigure.TestEntityManager`. `@MockBean` is gone; use
`@MockitoBean` from `org.springframework.test.context.bean.override.mockito`. Surefire loads the
Mockito agent via `-javaagent` (needs `maven-dependency-plugin:properties`). No new dependencies:
JUnit Jupiter 6, Mockito 5.23 and AssertJ 3.27 all arrive with the Boot 4 test starters.
**Tests:** 35 unit · 33 web slice · 10 persistence slice · 1 `@SpringBootTest`. Smoke test
unchanged at 34 checks.
**Gotchas:** `BigDecimal.equals()` compares scale, so `8999.00 != 8999.0` — assert money with
`isEqualByComparingTo`, and assert web bodies as JSON (JSONAssert compares numerically).
`new BigDecimal("1.00")` keeps its scale; `BigDecimal.valueOf(1.00)` does not. Repository tests
set `spring.sql.init.mode=never` so `data.sql` does not seed them. A `@WebMvcTest` needs
`@Import(GlobalExceptionHandler.class)` for the error-shape assertions to see the advice.
**Follow-ups (not done, out of scope):** coverage reporting — Phase 12. Tests against a real
PostgreSQL — Phase 7. The oversell race in checkout still has no test, because it cannot be
fixed until Phase 6.
