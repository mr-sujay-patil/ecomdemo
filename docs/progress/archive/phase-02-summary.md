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
