# Phase 02 Test Report: Automated Testing

- **Date:** 2026-09-21
- **Branch:** `feature/phase-02-testing`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1
- **Result:** ✅ all checks passed

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 79, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Nothing is `@Disabled` or skipped. Phase 1's single `@SpringBootTest` still runs unchanged; the
78 new tests sit underneath it.

| Test class | Type | Tests | What it proves |
|---|---|---|---|
| `ProductServiceTest` | unit (Mockito) | 13 | Every `ProductService` method, success and failure: mapping to DTOs, the shared 404, that a failed lookup saves and deletes nothing, and that `create` returns what the repository returned rather than the instance it built |
| `CartServiceTest` | unit (Mockito) | 12 | The single-cart rule (found vs. created on first use), a repeat add merging into the existing line instead of duplicating it, quantity replacement, line removal, the two "not in the cart" 404s, and that every failure path saves nothing |
| `OrderServiceTest` | unit (Mockito) | 10 | Checkout end to end, name/price snapshotting on each line, the empty-cart 409, the insufficient-stock 409, the exact-last-unit boundary, and that a short *later* line leaves the earlier line's stock untouched |
| `ProductControllerTest` | `@WebMvcTest` | 15 | Status codes, the `Location` header on 201, 204 with an empty body, and every validation rejection (blank name, missing price, missing stock, several fields at once, malformed JSON, unbindable path variable) |
| `CartControllerTest` | `@WebMvcTest` | 11 | Cart JSON shape including the server-calculated total, 200 (not 201) for adding a line, the 404s for an unknown product and an absent line, and the `quantity >= 1` / `productId` required rejections |
| `OrderControllerTest` | `@WebMvcTest` | 7 | 201 plus `Location` on checkout, both 409 cases reaching the client with different messages through one handler, and the order 404 |
| `CartRepositoryTest` | `@DataJpaTest` (H2) | 4 | `findCart()`: empty before anything exists, an empty cart still findable via the `left join`, items **and** their products loaded by the JOIN FETCH, and `distinct` collapsing the duplicate cart rows a collection join produces |
| `OrderRepositoryTest` | `@DataJpaTest` (H2) | 6 | `findAllWithItems()` ordering and line loading, `distinct` on a three-line order, `findByIdWithItems()` found/unknown, and an order with no lines still findable |
| `PlaceOrderFlowTest` | `@SpringBootTest` | 1 | Unchanged from Phase 1 |

Test count by layer: **35 unit · 33 web slice · 10 persistence slice · 1 full context**, which is
the test pyramid the phase asks for — widest at the bottom, one slow test at the top.

## 2. Application starts — `./mvnw spring-boot:run`

```
Tomcat started on port 8080 (http) with context path '/'
Started EcomdemoApplication in 1.97 seconds (process running for 2.118)
```

`grep -cE " ERROR | WARN "` over the startup log → **0**.

## 3. End-to-end smoke test — `scripts/smoke-test.sh`

```
Summary: 34 passed, 0 failed
SMOKE TEST PASSED
```

Exit code 0. No checks were added this phase and none were removed, exactly as the phase file
specifies ("No new checks. The existing script must still pass.").

## 4. "Done when" items

| Item | Status | How it was verified |
|---|---|---|
| Every service method has success and failure tests | ✅ | `ProductServiceTest`, `CartServiceTest`, `OrderServiceTest` — 35 tests. Every public method of all three services appears in at least one success and one failure test; `OrderService.findAll` has an empty-list case in place of an exception, since it has no failure mode of its own |
| `./mvnw clean verify` passes | ✅ | 79 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS |

## 5. Notes and deviations

- **Mockito agent.** Mockito was self-attaching its bytecode agent at the first mock, which the
  JDK warns about and a future JDK will refuse. Surefire now loads the agent on the command line
  (`-javaagent` via `maven-dependency-plugin:properties`), so the suite is quiet today and will
  not break on a JDK upgrade.
- **`server.port=9090`.** The line appeared in `application.properties` from outside this phase
  and was picked up by a blanket `git add`. It was reverted in `dd09d10` after checking with the
  user; the smoke test and README both assume the default 8080. The smoke test was run against
  both ports and passed in both.
- **JSON, not deserialised records.** Two assertions initially failed because
  `BigDecimal.equals()` compares scale as well as value, so a price that goes out as `8999.00`
  and comes back as `8999.0` is "not equal". The web tests now compare the JSON body itself
  (JSONAssert compares numbers by value) and the unit tests use `isEqualByComparingTo`.
- **Nothing needs manual verification this phase.** Every item above was run and its output
  recorded here.
