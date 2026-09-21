# Phase 06 Test Report: Transactions & Concurrency

- **Date:** 2026-09-22
- **Branch:** `feature/phase-06-transactions`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1,
  Flyway 12.4.0, PostgreSQL 18.6 (`postgres:18-alpine`, aarch64), Docker 29.7.2, curl 8.7.1
- **Result:** ✅ all checks passed (two springdoc advisory WARNs at startup, carried over from
  Phase 3 — see §7)

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 120, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 9.329 s
```

Nothing is `@Disabled` or skipped. 108 tests carried over from Phases 1–5 and 12 are new. Run
three times end to end, green each time — the suite now contains a test that uses real threads,
so "it passed once" is not the same claim it used to be.

| Test class | Type | Tests | What it proves |
|---|---|---|---|
| `ConcurrentCheckoutTest` | `@SpringBootTest` | 4 | Two threads buying the last unit leave one order, one 409 and zero stock; the loser's already-written stock reduction is rolled back; a rejection is audited even though its transaction rolled back; a success is audited against the id of the order it created |
| `OrderPlacementServiceTest` | Mockito | 9 | The six place-order rules moved here from `OrderServiceTest` when the unit of work moved into its own bean, plus three on what is audited from inside the transaction and what is not |
| `OrderServiceTest` | Mockito | 8 (was 10) | Rewritten around the retry budget: one attempt on success, a retry after an `OptimisticLockingFailureException` that then succeeds, a 409 once `MAX_ATTEMPTS` is spent, and no retry at all for a rejection on the merits |
| `FlywayMigrationTest` | `@SpringBootTest` | +1 (5 → 6) | V4 added `product.version` as `NOT NULL` with existing rows backfilled to 0, created `order_audit` and its index, and gave it **no** foreign key |

Test count by layer: **42 unit · 33 web slice · 10 persistence slice · 26 full context ·
9 configuration**.

Two limits worth stating plainly:

- The unit tests cannot test a transaction. `OrderPlacementService` is built with `new` in
  `OrderPlacementServiceTest`, so no proxy wraps it and `@Transactional` does nothing there at
  all. Atomicity and rollback are only ever proven in `ConcurrentCheckoutTest`,
  `PlaceOrderFlowTest` and the smoke test, which run against a real context and a real database.
- `ConcurrentCheckoutTest` races on H2 in PostgreSQL mode, not PostgreSQL. §5 runs the same race
  against the real server to close that gap; Phase 7 closes it for the whole suite.

`ConcurrentCheckoutTest` was also run on its own **10 consecutive times**, green every time, with
the application log confirming the race was real rather than two checkouts that happened to
arrive in sequence:

```
WARN ... [pool-3-thread-2] com.ecomdemo.order.OrderService : Checkout attempt 1 of 3 lost an
optimistic lock: Unexpected row count (expected row count 1 but was 0)
[update product set category=?,description=?,name=?,price=?,stock_quantity=?,version=?
 where id=? and version=?] for entity [com.ecomdemo.product.Product with id '11']
```

That statement — `where id=? and version=?`, 0 rows changed — is the whole phase in one line.

## 2. Phase acceptance — the "Done when"

> The concurrency test passes reliably, and failed orders leave no partial data.

| Claim | How it was verified |
|---|---|
| Passes reliably | 10 consecutive runs of `ConcurrentCheckoutTest` and 3 of the full suite, all green (§1); 3 consecutive smoke-test runs against PostgreSQL, the optimistic lock firing in each (§4) |
| Exactly one winner | `outcomes.filteredOn(Outcome::succeeded).hasSize(1)` — *exactly* one, so an oversell fails the assertion as loudly as a double failure would |
| No partial data | `theLosingCheckoutLeavesNoPartialData` gives the cart a plentiful line and a scarce one. The loser writes the plentiful line's new stock before the scarce line's versioned UPDATE is rejected, so the difference between an atomic checkout and a broken one is visible in one number: 8 if the transaction rolled back, 6 if it did not. Confirmed at 8 in the test **and** against real PostgreSQL in §5 |
| A failure leaves evidence | `aRejectedCheckoutStillLeavesAnAuditRow`: the transaction rolls back and the `REQUIRES_NEW` audit row is still there, with a null `order_id` |

## 3. Running the complete application — `./mvnw spring-boot:run`

The database was already at v3 from Phase 5, so this was the first migration of this project to
run against a live, populated schema rather than an empty one:

```
o.f.core.internal.command.DbValidate : Successfully validated 4 migrations
o.f.core.internal.command.DbMigrate  : Current version of schema "public": 3
o.f.core.internal.command.DbMigrate  : Migrating schema "public" to version "4 - add product version and order audit"
o.f.core.internal.command.DbMigrate  : Successfully applied 1 migration to schema "public", now at version v4
com.ecomdemo.EcomdemoApplication     : Started EcomdemoApplication in 2.46 seconds
```

One migration applied, not four. The ten seeded products and everything the earlier phases' smoke
runs had created were still there afterwards, now carrying `version = 0`. 0 ERROR lines.

## 4. End-to-end smoke test — `scripts/smoke-test.sh`

**78 passed, 0 failed, 0 skipped**, exit 0 — three consecutive runs.

New in this phase (the "Smoke test additions" of the phase file, plus two checks on V4's schema):

```
Flyway migrations
  PASS  flyway_schema_history shows V1-V4, all successful
  PASS  V4's order_audit table exists
  PASS  V4's product.version column exists

Transactions and concurrency
  PASS  a product with exactly one unit in stock is created
  PASS  the last unit is in the cart
  PASS  two simultaneous checkouts return exactly one 201 and one 409
  PASS  the one unit was sold once, so stock is 0
  PASS  exactly one order holds that product
  PASS  the cart is empty after the race
  PASS  the race left one PLACED and one REJECTED audit row
  PASS  no rejected attempt claims to have created an order
  PASS  checking out an empty cart returns 409
  PASS  and says why
  PASS  the race probe product is cleaned up
```

One thing here was got wrong first and is worth recording. The two checkouts were originally two
backgrounded `curl` processes. The check passed — but the application log showed no optimistic
lock failure at all, because each `curl` pays roughly 10 ms of process start-up while a checkout
takes about 5 ms, so the second request usually arrived after the first had already committed and
was refused for an empty cart instead. Right answer, wrong reason, and a check that would have
kept passing if the locking were removed. Firing both from **one** `curl` with
`--parallel --parallel-immediate` makes them genuinely overlap: the versioned UPDATE is now
rejected in the log on every run (verified by counting the log line before and after each of the
three runs — exactly one per run).

The audit assertions need SQL rather than HTTP and use the same `psql`-or-container helper Phase 5
added, with the same **SKIP**-and-say-why fallback. Docker was available for all three runs, so
nothing was skipped.

## 5. Failure scenarios

Both were run against the running application and real PostgreSQL, not H2.

**(a) Contention past the retry budget.** One unit in stock, **six** simultaneous checkouts:

```
   1 201
   5 409
stock now: 0        orders holding it: 1        ERROR lines in log: 0
```

Exactly one winner, no 500s, no oversell. Worth noting honestly: only one of the five losers lost
an optimistic lock. The other four started after the winner had committed and were refused for an
empty cart — the cheaper rejection, which is the system behaving well rather than the test being
weak. The retry budget is exercised as designed either way: nothing hung, and no request came back
with anything but a 201 or a 409.

**(b) Rollback of a partially written order.** A cart holding 2 of a plentiful product (stock 10)
and 1 of a scarce one (stock 1), checked out twice at once:

```
201,409
optimistic lock failures logged: 1
bulk stock: 8     (10 - the winner's 2; it would be 6 if the loser's write had survived)
scarce stock: 0
orders holding bulk: 1
ERROR lines in log: 0
```

The loser had already written the plentiful product's new stock when the scarce product's
versioned UPDATE was rejected. 8 is the transaction undoing that write.

## 6. What was deliberately *not* done

- **No pessimistic locking.** `SELECT ... FOR UPDATE` would also prevent the oversell, by making
  every checkout of a popular product queue behind one row lock. Optimistic locking is the right
  default for a catalogue that is browsed far more than it is sold from, and the retry budget is
  what makes it honest.
- **No isolation level was changed.** Everything here works at the default READ COMMITTED. The
  lost update is prevented by the version column, not by the database's isolation level —
  SERIALIZABLE would also prevent it and would cost far more.
- **No retry annotation.** A hand-written three-attempt loop is easier to read, and to explain,
  than Spring Retry's proxying, and this phase is already about one proxy pitfall.

## 7. Startup log

0 ERROR. 2 WARN, both springdoc's, unchanged since Phase 3 and deliberate:

```
SpringDoc /v3/api-docs endpoint is enabled by default. To disable it in production, set the
property 'springdoc.api-docs.enabled=false'
SpringDoc /swagger-ui.html endpoint is enabled by default. To disable it in production, set the
property 'springdoc.swagger-ui.enabled=false'
```

Phase 8 decides access to those paths. The `WARN ... lost an optimistic lock` lines are this
phase's own and are expected whenever two checkouts collide; they are the retry working.

## 8. Cleanup

The application was stopped after testing and every probe product created by the failure
scenarios (`Stampede Probe`, `Rollback Bulk`, `Rollback Scarce`) was deleted through the API.
`GET /api/products` afterwards returns 11: the ten seeded products, plus the `Persistence Probe`
that Phase 4's smoke test leaves behind on purpose for the next run to find.

The `ecomdemo-postgres` container is left **running** with the schema at v4, so the next session
can start the application immediately; `docker start ecomdemo-postgres` if it is down. No stray
processes. `.smoke-state` is untracked, as before.

## 9. Manual verification needed

None. Every "Done when" and every smoke-test addition is covered by an automated check above.
