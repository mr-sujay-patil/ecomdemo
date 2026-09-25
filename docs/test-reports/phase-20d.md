# Phase 20d Test Report: the last two services, and Phase 20 complete

- **Date:** 2026-09-25
- **Branch:** `feature/phase-20d-customer-service`
- **Toolchain:** Spring Boot 4.1.1, Spring Modulith 1.4.1, PostgreSQL 18.6, Redis 8, Apache Kafka
  4.2.1, Loki 3.7.8, Alloy v1.19.2, Testcontainers 2.0.5, JDK 21, Docker 29.8.0
- **Result:** ✅ green. Smoke test **274 passed / 0 failed**, twice, from a cold stack.
- **Scope:** the fourth and final PR of Phase 20. **Five services. `phase-20-complete` is tagged when
  this merges and verifies.**

## 0. ❗ Read this section first: two phases of integration tests never ran

**The single most important finding of Phase 20, and it is a correction to what earlier reports
claimed.**

The parent pom declares Failsafe in `<pluginManagement>`, so a module opts in by naming it — the same
arrangement as Surefire. `ecomdemo-app` names it. **The four services extracted in 20b, 20c and 20d
did not.**

An unbound plugin runs nothing and reports nothing. There is no "0 tests" line, no warning, no skip
count. `./mvnw clean verify` printed BUILD SUCCESS throughout. So `CacheApiIT`, `ProductApiIT`,
`AuthApiIT` and `OrderPlacedConsumerIT` were written, reviewed, committed, and **described in the
20b and 20c test reports as part of a passing suite, without ever having been executed.**

Binding them found five real defects in minutes:

1. **Every catalog-service IT failed with 500 on create.** That service calls inventory-service on
   every product write *and asks it for the stock figure on every read*; nothing faked it.
2. **A `@BeforeEach` in a test base was package-private**, and every subclass is in a different
   package — so Java never inherited it and JUnit never called it. `rest` stayed null. Two invisible
   failures stacked on each other.
3. **Neither catalog-service nor customer-service set the resource server's own
   `authenticationEntryPoint`**, so a tampered or expired token came back with an empty body instead
   of an `ApiError`. The application's chain carries a comment warning about exactly this, written in
   Phase 9 — *a comment on one class does not configure another*.
4. **notification-service had no `SecurityConfig`** (the 20b trap) and no `testcontainers-kafka`.
5. **`ProductApiIT` and `AuthApiIT` still asserted rules that had moved to the edge.**

`EveryModuleWithIntegrationTestsRunsThemTest` now fails the build if a module with `*IT` files does
not name Failsafe. Its sibling guards the Dockerfile's per-module `COPY` list, which has caught the
same class of staleness **three times** — once per extracted service.

**The lesson, stated plainly: a green build only means the things that ran passed.** Every other
finding in this report is downstream of taking that seriously.

## 1. Full regression

    common                10      inventory-service     34
    catalog-service       41 + 12 ITs                     customer-service  43 + 8 ITs
    notification-service   8 + 3 ITs                      ecomdemo-app     227 + 53 ITs
    BUILD SUCCESS — and FOUR modules now show a failsafe run where one did

## 2. The number that matters

    scripts/smoke-test.sh  ->  274 passed, 0 failed, 0 skipped   (twice, cold stack)

Sixteen containers: five services, five databases, Redis, Kafka, and the observability stack.

## 3. Memory: the 20a risk, conclusively closed

    app 187 MiB · catalog 149 · notification 142 · inventory 132 · customer 72
    kafka 388 · prometheus 118 · alloy 97 · loki 83 · grafana 45
    five databases 17-21 MiB each
    TOTAL 1459 MiB against a 3916 MiB ceiling

Phase 20a called memory *"the open risk"*, projecting **296 MiB per service** and 2.0–2.4 GB overall
by extrapolating from the monolith. Measured: services idle at **72–187 MiB**, and the total for five
is **1459 MiB** — less than the three-service measurement in 20c, because **the application shrank
from 343 MiB to 187 MiB as code left it.** Extrapolating a service's cost from a monolith
overestimates it badly, and the error compounds in the reassuring direction.

## 4. ❗ The data change that had exactly one window

`Order.getUsername()` read through the association to `users`, and that name appears in the API
response and the audit row. New orders can take it from a token claim; **the 686 orders already in
the table had it nowhere.** Once `users` lives in customer-service there is no query that can fill it
in — no join, no subselect, just ten thousand HTTP calls.

So V15 snapshots and backfills it, in the last migration that can see both tables. All 686 rows
verified populated against the real database. **The plan did not predict this**; the compiler did,
when `getUsername()` stopped compiling.

That is the 20a lesson in its sharpest form: a data change needing both halves has exactly one
window, and it closes when the services separate.

## 5. Identity comes from the token

`CurrentUser` moved to `common` **without `require()`**. The id and username were always read from
claims; only that one method loaded an account, and across a boundary it would be an HTTP call on
every authenticated request to learn what the request already carried. The lookup now lives in
`CustomerService`, the one service that legitimately has the table.

**The cost, stated plainly:** `cart.user_id` and `orders.user_id` have no foreign key behind them.
Deleting an account leaves its cart and orders behind. The database used to prevent that; nothing
does now. Same trade as V12 made for `cart_item.product_id`.

The app's tests **mint tokens** rather than logging in, following `CatalogIntegrationTest` from 20c.
`TestAuthentication` was already building a `Jwt`; the account row around it was scaffolding. The
fake BCrypt hash in test data is gone with it, which is a small improvement on its own — nothing ever
verified it, so a plausible-looking credential in test data was a liability with no purpose.

## 6. Where every moved test went

Nothing was deleted to make this compile. Each relocation left a comment at the old site naming the
new one, and each claim was verified in its new home *before* the old one was removed.

| Claim | From | To |
|---|---|---|
| `require()` loads the account | `CurrentUserTest` | `CustomerServiceTest` — the service that owns the table |
| V5's users table and seeded admin | `FlywayMigrationTest` (app) | customer-service's own schema test |
| A token's roles decide what it may do | `AuthApiIT` | split: this service proves what it *writes* into a token; `ProductProxyAccessTest` proves what the edge *permits* |
| Anonymous reads, ADMIN writes | `ProductApiIT` | `ProductProxyAccessTest` (app) |
| One notification per order; redelivery writes one | `OutboxRelayKafkaIT` (app) | `OrderPlacedConsumerIT` (notification-service) |
| A rejected checkout publishes nothing | `OrderPlacedKafkaIT` | stayed in the app — it is a producer claim |

`OrderPlacedConsumerIT` publishes to the topic **as a map**, not as its own record: round-tripping
through one definition would pass however that definition drifted, which is exactly the risk of not
sharing a jar.

## 7. Three more things that only running found

**`TokenView` invented its field names.** customer-service returns `expiresIn` and `expiresAt`; the
guess produced a **400 "Malformed request body" on every login** — thrown while reading the
*response*, which happens to be the same exception type Spring throws for an unreadable *request*, so
the application's own handler blamed the caller. A contract written from memory is a contract got
wrong, and its error can point in entirely the wrong direction.

**The broker-outage timing check needed warming up, and the first two attempts made it worse.** The
relay **stops the batch at the first failure** — a deliberate Phase 18 decision so event N+1 never
precedes a failed N. A warm-up row still pending when Kafka stops becomes the row retried for ever,
while the row under test queues behind it with `attempts` at zero. The checks then read an untouched
row and conclude the relay is idle when it is retrying furiously. The warm-up now *drains* first.

**Its retry window went from 15s to 40s.** `attempts` increments only *after* a send fails, and one
failure costs `max.block.ms` + `delivery.timeout.ms`. Fifteen seconds left room for one attempt and
no slack — survivable at nine containers, not at sixteen. A budget doubling as an undeclared
performance assertion is a flake waiting for a slower machine.

## 8. Package placement as enforcement

Two placements look arbitrary and are load-bearing:

- The inventory client cannot be `com.ecomdemo.inventory` — inventory-service scans that package and
  would build a `RestClient` pointing at itself.
- `ServiceIdentityConfig` cannot live in `com.ecomdemo.jwt`. Every service scans it, because everyone
  must *verify*; only a caller needs to *sign*. inventory-service refused to start when asked for a
  `JwtEncoder` it has no reason to own. **"Everyone verifies, only callers sign" is now enforced by
  which package the bean is in** rather than by anyone remembering it.

Also: both `CatalogServiceApplication` and `CustomerServiceApplication` sit in `com.ecomdemo`, not
their feature package. Boot finds a test's configuration by searching *upwards*, and these services
span sibling packages — the root is the only common ancestor.

## 9. ⚠️ Carried, not fixed

- **The Phase 19 dashboard defect.** Two `EcomDemo Overview` stat panels reduce an instantaneous rate
  with `lastNotNull`. Its own `fix/dashboard-stat-reducers` branch. Now the oldest open item.
- **A failed compensating release leaks a reservation.** Nothing reconciles it.
- **The CSV import is a distributed write** with no shared transaction; restartable and idempotent, so
  the repair is to re-run it.
- **HS256 with a shared secret.** Every service can mint as well as verify. The fix is asymmetric keys
  and a JWKS endpoint.
- **`ecomdemo-app` is not yet named `order-service`.** It contains only order-service's code and owns
  only its tables; the rename is cosmetic and would touch every image tag, compose service and CI
  reference. Deliberately left as its own change.
- **Two hand-written proxies** (`/api/products`, `/api/auth` + `/api/customers`) keep the split
  invisible. Phase 21's API gateway replaces them — and in the login case fixes something real: a
  proxied login puts a plaintext password through the application's memory.

## 10. Environment left behind

Sixteen containers up and healthy. `ecomdemo` at Flyway **V16**, `catalog` **V2**, `customer` **V1**,
`inventory` **V2**, `notification` **V1**. `kafka-ui` behind `--profile tools` — remember
`docker compose --profile tools down`, or the network survives the stop.
