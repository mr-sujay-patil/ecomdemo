# Phase 01 Test Report: Baseline Monolith

- **Date:** 2026-09-21
- **Branch:** `feature/phase-01-baseline-monolith`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1
- **Result:** ✅ all checks passed

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

One test at this phase, as the phase file specifies ("One `@SpringBootTest` verifying the
place-order flow"; extra tests are explicitly out of scope and arrive in Phase 2). Nothing is
`@Disabled` or skipped.

| Test | Type | What it proves |
|---|---|---|
| `PlaceOrderFlowTest` | `@SpringBootTest` (full context + H2) | Cart total, order total, PLACED status, price/name snapshot, stock decrement, cart emptied, order readable afterwards, empty-cart checkout rejected |

## 2. Application starts — `./mvnw spring-boot:run`

```
Started EcomdemoApplication in 1.771 seconds (process running for 1.903)
```

**Zero ERROR or WARN lines in the startup log.** The one warning present initially
(`spring.jpa.open-in-view is enabled by default`) was fixed rather than ignored — see §5.

## 3. End-to-end smoke test — `scripts/smoke-test.sh`

**34 checks, 34 passed, 0 failed, exit code 0**, run against a cold-started application.

| Group | Checks | Covers |
|---|---|---|
| Readiness | 2 | App responds; cart emptied so the run is repeatable |
| Happy path | 20 | list products → add to cart → view cart (server total) → place order → read order back → order in list → stock decreased → cart emptied |
| Negative cases | 12 | 404 unknown product, 404 unknown product added to cart, 404 removing a line that is not there, 400 quantity 0, 400 missing productId, 409 over-stock checkout, 409 empty-cart checkout, error-body shape, and that a failed checkout leaves stock untouched |

The script's own failure detection was verified: pointed at a dead port it prints FAIL and exits
**1**; against the running app it exits **0**. Run twice in a row it passes both times.

## 4. "Done when" verification

| Item | Result | How it was verified |
|---|---|---|
| `./mvnw spring-boot:run` starts the app | ✅ | Startup log above, no errors or warnings |
| curl flow: list → add → view → place → view order → stock decreased | ✅ | Smoke test happy path, 20 checks |
| PR merged and verification checklist passes | ⏳ | Pending your review and merge |

## 5. Issues found and fixed during testing

**`LazyInitializationException` when adding to the cart.** `CartRepository.save()` on a
detached cart performs a *merge*: Hibernate loads a fresh managed copy and returns that, and in
the copy the LAZY `product` association is an uninitialised proxy. Mapping the response then
touched `product.getName()` after the session had closed. Fixed by re-reading the cart through
the `JOIN FETCH` query after saving, which costs one extra query and returns a fully populated
graph. The alternative fix, `@Transactional`, is Phase 6's scope and was deliberately not used.

**`spring.jpa.open-in-view` warning.** Enabled by default, it holds the Hibernate session open
for the entire HTTP request, so lazy associations silently resolve during JSON serialisation.
That would have masked the bug above in the web layer while tests still failed, and it hides
N+1 queries in general. Set to `false` explicitly; the `JOIN FETCH` queries make it
unnecessary. Re-ran `clean verify` and the full smoke test afterwards — both green.

## 6. Known gaps (deliberate, not defects)

- **Checkout is not atomic.** Stock is validated for every line before anything is written, so
  a rejected checkout changes nothing (the smoke test asserts this). But each save commits
  separately, so a crash mid-write could reduce stock without an order, and two concurrent
  checkouts can both pass the stock check and oversell. **Phase 6** closes this with
  `@Transactional` and optimistic locking.
- **No authentication and one shared cart** — Phases 8 and 9.
- **In-memory H2, schema recreated each start** — Phases 4 and 5.

## 7. Cleanup

Application stopped, port 8080 released, no stray processes or files. Build output
(`target/`) is git-ignored.

## Commands to reproduce

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw clean verify
./mvnw spring-boot:run          # terminal 1
scripts/smoke-test.sh           # terminal 2
```
