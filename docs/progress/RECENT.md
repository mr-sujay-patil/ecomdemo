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

## Phase 02: Automated Testing (tag: pending, PR: pending)
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

## Phase 01: Baseline Monolith (tag: phase-01-complete, PR #1)
**What exists now:** A running Spring Boot 4.1.1 monolith on H2 in-memory: product CRUD, one
shared cart with a server-calculated total, and checkout that validates stock, reduces it, saves
the order and empties the cart. All endpoints under `/api`, errors as `{status, message}`.
**Key code:** `com.ecomdemo.{product,cart,order,common}`, package-by-feature, Controller → Service
→ Repository. `CartService.currentCart()` is the single-cart rule. `OrderService.place()` is the
checkout flow. `GlobalExceptionHandler` maps 404/400/409.
**Config & infrastructure:** `application.properties` — H2 at `jdbc:h2:mem:ecomdemo`,
`ddl-auto=create-drop`, `defer-datasource-initialization=true`, `open-in-view=false`, H2 console
at `/h2-console`. `data.sql` seeds 10 products (product 10 has stock 2, used by the 409 check).
Port 8080. Build with JDK 21: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
**Tests:** `PlaceOrderFlowTest` (`@SpringBootTest`, the only test this phase by design).
`scripts/smoke-test.sh` — 34 checks, re-runnable, exits non-zero on failure.
**Gotchas:** `save()` on a detached entity merges and returns a copy with *uninitialised* lazy
proxies — cart writes therefore re-read via `CartRepository.findCart()` (JOIN FETCH) before
mapping. `open-in-view=false` means repositories must fetch everything the caller needs. Order's
table is `orders` (ORDER is reserved). Order lines snapshot name and price on purpose.
**Follow-ups (not done, out of scope):** checkout is not atomic and can oversell under
concurrency — Phase 6 (`@Transactional` + `@Version`). No unit/slice tests yet — Phase 2.
