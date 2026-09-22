# Phase 19 Test Report: Modular Monolith

- **Date:** 2026-09-23
- **Branch:** `feature/phase-19-modulith`
- **Toolchain:** Spring Boot 4.1.1, Spring Modulith 1.4.1 (`-api` compile, `-core`/`-docs` test),
  ArchUnit (via Modulith), PostgreSQL 18.6, Redis 8, Apache Kafka 4.2.1, Loki 3.7.8, Alloy v1.19.2,
  Testcontainers 2.0.5, JDK 21, Docker 29.7.2, Compose v5.4.0
- **Result:** ✅ green. `./mvnw clean verify` **319 + 82**, 0 failures, 0 skipped; smoke test
  **270 passed, 0 failed, 0 skipped — unchanged**, which for this phase is the whole point.
  No schema change, no new endpoint, no new container.

## 1. Full regression — `./mvnw clean verify`

    [INFO] Tests run: 319, Failures: 0, Errors: 0, Skipped: 0     <- Surefire (was 310)
    [INFO] Tests run: 82,  Failures: 0, Errors: 0, Skipped: 0     <- Failsafe (unchanged)
    [INFO] BUILD SUCCESS
    [INFO] Total time:  01:58 min

Nine new unit tests: two in `ModularityTest`, three in `StockMutationRulesTest`, four in
`EventDeduplicatorTest`. The integration count is unchanged, which is expected — this phase moved
code and changed no behaviour.

## 2. The "Done when", and why the smoke count matters more than the test count

The phase's criterion is *the verification test passes, and the diagrams show clean dependencies*.
Both hold. But the number that actually carries this phase is the other one:

    scripts/smoke-test.sh  ->  270 passed, 0 failed, 0 skipped     (Phase 18: 270)

**Exactly the same 270 checks, unchanged.** This phase adds none, by design, so the existing suite
is the entire regression net for a refactor that moved roughly 90 files, renamed two packages,
created two modules and changed three call paths. A single altered check would have meant the
refactor changed behaviour. None did.

## 3. What the verification test found, before anything moved

The test was written **first**, against the Phase 18 layout. That was the most useful decision in
the phase and it is worth recording what it bought.

It found **two** violations, not the dozens a guessed module map would have assumed:

    - Cycle detected: Slice customer -> Slice security -> Slice customer
    - Module 'cache' depends on non-exposed type com.ecomdemo.catalog.dto.ProductResponse

And the real dependency graph:

    order    -> customer, cart, catalog, messaging, metrics, security, shared
    cart     -> catalog, customer, security, shared
    security -> customer, shared      ] the cycle
    customer -> security, shared      ]
    batch    -> catalog, shared    cache -> catalog    notification -> messaging
    auth     -> security, shared   catalog -> shared
    shared, logging, messaging, metrics -> (nothing)

**The cycle was one class.** `CurrentUser` was the only `security` type that `cart`, `order` and
`customer` imported. Moving it into `customer` broke the cycle *and* removed `cart -> security` and
`order -> security`: three edges for one file. A module map drawn from memory would not have found
that, and would have proposed a much larger refactor to fix a much smaller problem.

## 4. The graph now, declared and enforced

Every module carries `@ApplicationModule(allowedDependencies = …)`, so this is not a description —
it is a constraint the build holds the code to:

    shared, logging, metrics, messaging  ->  (nothing)
    catalog, customer                    ->  shared
    notification                         ->  messaging
    cache                                ->  catalog, catalog::dto
    inventory                            ->  catalog, shared
    cart                                 ->  catalog, customer, shared
    security                             ->  customer, shared
    auth                                 ->  security, shared
    batch                                ->  catalog, inventory, shared
    order  -> cart, catalog, customer, inventory, messaging, metrics, shared

Acyclic. `shared` and `messaging` depend on nothing, and their empty lists are the load-bearing
ones — a shared module that depends on other modules is a layer, and `messaging` depending on
nothing is what would let the outbox serve a second aggregate without being taught about it.

## 5. The boundary a test has to hold, because the compiler cannot

`catalog` and `inventory` share one table and one entity — the approved decision was to split the
behaviour, not the schema. The cost shows up immediately: `Product.reduceStock` and
`setStockQuantity` are public methods on an exposed type, so nothing in Java stops another module
calling them, and Modulith cannot see it either (it checks which *packages* reach into which; this
is one exposed type used two ways).

`StockMutationRulesTest` is the ArchUnit rule that does. **Verified by mutation**: putting
`product.reduceStock(...)` back into `OrderPlacementService` fails it.

The rule permits **two** modules, not one, and the test says so out loud. `ProductService.update`
is the admin's full replace and cannot call into `inventory` without forming a cycle back through
the dependency `inventory` already has on `catalog`. Two modules sharing an entity means the owner
keeps access to all of it; no arrangement of tests changes that. What the rule does buy is real:
`order` and `batch` both mutated stock directly before this phase, and neither can now.

## 6. Three repositories that were crossing module lines

| Was | Now | Why not just expose the repository |
|---|---|---|
| `security` → `customer.UserRepository` | `customer.UserDirectory`, 2 methods | A repository is a module's entire data surface, and it keeps growing |
| `batch` → `catalog.ProductRepository` | `ProductService.findFirstByName` / `saveAll` | The "oldest match wins" rule now lives on the API, not in a method name callers had to know to pick |
| `notification` → `messaging.ProcessedEventRepository` | `EventDeduplicator.claim()` | The caller no longer has to know the marker goes in FIRST, or why |

Each replacement is a better API than the thing it hides, which is the test of whether a boundary
was worth drawing.

The idempotency assertions moved from `NotificationServiceTest` into a new `EventDeduplicatorTest`
— **nothing was dropped**, it is asserted where the behaviour now lives and where the next consumer
will inherit it rather than reimplement it.

## 7. Traps hit

**Spring Boot 4.1.1 does not manage Spring Modulith**, and the newest GA line targets Boot 3.5.
Resolved by splitting the dependency rather than by hoping: `spring-modulith-api` at compile scope
(annotations only — all three of its dependencies are declared `optional`, so nothing transitive
comes with it), `-core` and `-docs` at test scope. Neither ever assembles an application context,
which is what makes the version gap safe. Taking `spring-modulith-starter-core` would have put
Boot-4-untargeted auto-configuration on the runtime classpath.

**`@NamedInterface` and `@ApplicationModule` go on main source**, which is why the `-api` jar could
not also be test-scoped. The first attempt was, and failed with `package org.springframework.modulith
does not exist` in a `package-info.java`.

**Moving package-private classes into `internal` breaks their tests**, because the tests sat in the
module root package. The fix is to move the tests into a matching `internal` test package —
mirroring production — rather than to widen visibility to suit the tests. 23 test classes moved.

**`Documenter` omits `logging` from the overall component diagram.** It has no dependencies and no
dependents, so it appears as an isolated node that the C4 rendering drops. Its own
`module-logging.puml` and canvas are generated normally. Cosmetic, noted so it is not mistaken for
a missing module.

## 8. What this phase deliberately did NOT do

**The `product` table was not split.** `stock_quantity` is still a column on `product` and
`Product` is still one entity. Splitting it into `product_stock` is the textbook bounded-context
answer and would have touched the Phase 12 optimistic locking, the Phase 16 cache eviction and the
CSV import — in a phase whose smoke test gains no new checks to catch what broke. Recorded in
`docs/decisions.md`; Phase 20 is where it belongs if the service split demands it.

**`order -> cart` stayed a direct call.** The phase asks for events "where appropriate", and this
is where it is not: `clearCart` runs inside the checkout transaction, and turning it into an event
would either break that atomicity or need a second outbox for no benefit. Phase 16 already made
`order -> cache` an event and Phase 18 made `order -> notification` one, so the genuinely
event-shaped edges were already events before this phase started.

**The cache still knows that products exist.** The sharper design lets `catalog` contribute its own
cache configuration, inverting the dependency instead of declaring it. That is a change to caching
rather than to boundaries, so it is recorded as considered and deferred.

## 9. ⚠️ Still outstanding for the user's eyes

Unchanged by this phase, and carried since Phase 15:

- The **Grafana dashboards' render** has never been looked at by a human. Every number behind them
  is verified; Chrome's site permissions block `localhost:3000` for browser automation here.
  Steps: `docs/test-reports/phase-15.md` §8.
- **Kafka UI at http://localhost:8090**, likewise unseen.

Both need `docker compose up -d` first — see below.

## 10. Environment left behind

The compose stack is **DOWN**. It was brought up to run the smoke test and shut down again at the
user's request. Named volumes were kept, so `docker compose up -d` restores the data; the schema on
that volume is **V10**, unchanged by this phase. Docker Desktop's daemon dropped its socket once
during the rebuild and came back on its own — the retry succeeded with no intervention, noted only
because the first `compose up` failed for a reason that had nothing to do with the code.

The SonarQube stack is also stopped; `docker compose -f compose.sonar.yaml up -d` brings it back.

One deliberate side effect to know about: `ModularityTest` **writes into `docs/modules/`** on every
run, so `./mvnw test` touches the working tree. That is the point — a stale diagram shows up as an
uncommitted change rather than as nothing at all.
