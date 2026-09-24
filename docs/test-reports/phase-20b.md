# Phase 20b Test Report: Extracting inventory-service

- **Date:** 2026-09-24
- **Branch:** `feature/phase-20b-microservices`
- **Toolchain:** Spring Boot 4.1.1, Spring Modulith 1.4.1, PostgreSQL 18.6, Redis 8, Apache Kafka
  4.2.1, Loki 3.7.8, Alloy v1.19.2, Testcontainers 2.0.5, JDK 21, Docker 29.7.2, Compose v5.4.0
- **Result:** ✅ green. `./mvnw clean verify` **356 + 81**, 0 failures, 0 skipped; smoke test
  **270 passed**, 0 failed, 0 skipped — run twice, both clean.
- **Scope:** ⚠️ **This is the second half of Phase 20, and it does not finish the phase's original
  plan.** One service is extracted, not five. See §8.

## 0. What this PR actually delivers

`inventory-service` is a separate deployable, with its own database, its own Flyway history, its own
filter chain and its own Kafka topic. The application talks to it over HTTP. Eleven containers come
up healthy and the full smoke suite passes against them.

**The plan named five services and this delivers one.** That is a deliberate stop, not an
abandonment, and §8 says why: extracting the first one surfaced four whole classes of problem that
the plan had not predicted, every one of them invisible to the test suite and found only by running
the stack. Those lessons belong in the repository before the same mistakes are made four more times.

## 1. Full regression

    [INFO] Tests run: 5,   Failures: 0, Errors: 0, Skipped: 0     <- ecomdemo-common (new module)
    [INFO] Tests run: 34,  Failures: 0, Errors: 0, Skipped: 0     <- inventory-service
    [INFO] Tests run: 317, Failures: 0, Errors: 0, Skipped: 0     <- ecomdemo-app (was 334)
    [INFO] Tests run: 81,  Failures: 0, Errors: 0, Skipped: 0     <- Failsafe (was 83)
    [INFO] BUILD SUCCESS

The app's unit count falls because tests moved to the service that now owns what they assert;
`common` gains a test suite because it gained its first real behaviour.

## 2. The number that matters

    scripts/smoke-test.sh  ->  270 passed, 0 failed, 0 skipped

The same 270 as Phase 19 and 20a, now spanning **two services and two databases**. Two checks
changed what they assert and one changed how it measures; none was added, removed or weakened to
get here. §5 is about the one whose *meaning* changed.

## 3. ❗ Four failures nothing but a running stack could find

This is the section worth reading. Every one of these passed `./mvnw clean verify`.

**① Every endpoint answered 401.** inventory-service was extracted with the resource-server starter
on its classpath and no filter chain of its own, so Boot's fallback secured every path with a
generated password nobody had. The service started, reported itself **healthy** — the health probe
being the one path the fallback leaves open — and every call from the application came back 401,
surfacing as a 500 on the product listing three layers away.

**② The new database was empty.** The extraction moved the `product_stock` *table* and left its
*contents* behind. inventory_db began at V1 with an empty table, so all ten seeded products read as
zero stock. Nothing threw: a missing stock row reads as zero by design (Phase 20a), which is the
right decision and precisely why this could not fail loudly. The catalogue happily listed ten
products nobody could buy.

**③ The stock event was deserialized as an `OrderPlacedEvent`.** `spring.json.value.default.type` is
a *global* consumer property, correct and invisible while one topic was being consumed. The producer
sends no type header (deliberately — a type header is a Java class name on the wire), so the second
topic inherited the first topic's type. **The consumer group reported zero lag the entire time**:
records were consumed, failed, retried, exhausted and discarded, so the offset advanced exactly as
it does when everything works. Every instrument said healthy. The only symptom was a catalogue
advertising stock it had already sold.

**④ The new containers shipped no logs.** Alloy's `keep` rule named `app` and nothing else, so
inventory-service and inventory-db were discovered and then discarded. Querying Loki for a service
that ships no logs and for one that does not exist look identical.

**What they have in common** is the point: each is a *configuration* that was correct for one
service and silently wrong for two. None is a logic error, so no unit test could see it; each was
found by the smoke test or by a test written against a real filter chain afterwards. Every one now
has a test — `InventorySecurityTest`, `SeededStockAgreesWithTheCatalogueTest`,
`StockChangedListenerConfigTest`, and `alloyShipsEveryServiceThisRepositoryBuilds` — and each of
those was **mutation-checked**: broken deliberately, observed to fail, restored.

## 4. Two defects the new tests found immediately

Writing a test that calls the service *through* its filter chain found both within minutes.

**`@PreAuthorize("hasRole('ADMIN')")` on two endpoints could only ever answer 403.** Carried over
from the monolith, where the administrator's own token arrived. The caller is now a service
presenting `ROLE_SERVICE`, so product creation, product update and the CSV import would all have
broken. It was **also inert**: method security is off without `@EnableMethodSecurity`, which this
service never enabled, so neither annotation had ever been evaluated. An annotation that is both
unsatisfiable and inactive is worse than none, because it reads like protection.

**A missing field returned 500 instead of 400.** One `QuantityRequest` served three endpoints each
needing a different two of its three fields, so no field could carry `@NotNull` — and `@Positive`
alone is satisfied by `null`. A body without `units` passed validation and threw unboxing it. Split
into one record per request shape, and the call sites stop reading `new QuantityBody(null, q, null)`.

## 5. ❗ The one behaviour that changed

**Cache eviction is no longer immediate.** The smoke test used to assert, with a comment saying so
at length, that no sleep was needed because "the eviction is part of finishing the checkout". That
was true of a monolith. inventory-service now owns the stock and publishes `inventory.stock-changed`;
the catalogue's evictor consumes it. Between checkout returning 201 and the cache being evicted
there is a broker, a poll interval and a network.

The check now polls and **reports the convergence time** (`took 0ms` in practice — the first poll
already sees it) rather than sleeping a fixed amount, so a window quietly growing from 200ms to 4s
becomes visible instead of merely still passing.

**What did not become eventually consistent:** a shopper is never sold stock that is not there. The
reservation is synchronous and inventory-service holds the row lock. The catalogue may briefly
*advertise* a stale number; checkout still refuses. Stale display, correct sale.

## 6. Security across a boundary

**The application calls inventory-service as itself**, with a short-lived HS256 token, subject
`ecomdemo-app`, role `SERVICE`. Token relay would be better where it works, and it does not work
here: the product listing is anonymous and shows stock, and the CSV import sets stock from a
background thread with no `SecurityContext`. Rather than relay when convenient and invent something
when not — leaving the interesting case untested — the caller always authenticates as itself.

**The cost, stated plainly:** inventory-service cannot tell an administrator from a shopper, so
authorisation stays at the edge. That is the usual shape, and it is safe only while the internal
service is unreachable from outside — which today is a claim about a laptop, since its port is
published for the smoke test.

**HS256 is kept, knowingly.** Phase 9's comment has come true word for word: *"The moment a second
service needs to accept these tokens, HMAC becomes the wrong choice."* `JWT_SECRET` now reaches
every service, so any of them could mint a token claiming to be any administrator. The fix is
asymmetric keys and a JWKS endpoint, which is a phase of its own; the encoder bean stays in the one
module that owns logins so the intended shape survives that move.

## 7. Two build lessons

**The Dockerfile built the whole reactor once per service.** A BuildKit stage that reads an `ARG` is
a different stage for each value of it, so `ARG MODULE` inside the build stage forked it. Two
parallel Maven builds on a 3.8 GB Docker VM hung for twenty minutes and dragged Kafka, Grafana and
Prometheus into unhealthy. Extraction moved to its own stage; the build stage is now shared and runs
once. At five services this is the difference between one reactor build and five.

**`common` now publishes a test-jar** so every service can share `ProjectRoot` rather than copying a
helper whose entire purpose is to avoid a per-module assumption. One side effect had to be handled:
the jar is on the test classpath, Modulith scans the classpath, and `com.ecomdemo.support` began
appearing in the generated architecture diagrams as an application module. `ModularityTest` now
excludes it — a test helper is not an application module however it is spelled.

## 8. ⚠️ What is NOT done, and the recommendation

Still to extract: **catalog-service** (which takes `cache` with it), **customer-service** (which
issues tokens), **notification-service**; `order-service` is what `ecomdemo-app` becomes.

**Recommendation: one service per PR from here, not four in one.** The evidence is §3. Extracting
the first service produced four distinct failures, none visible to the build, each found only by
running the stack — and they are not first-time-only problems. Every future extraction will need its
own filter chain, its own seed data carried across, its own deserializer if it consumes a new
message type, and its own line in Alloy's keep rule. The tests added here turn three of those four
into build failures for the *next* service, which is most of the value of having paid for them once.

Doing the remaining four in one PR would stack four times that surface behind a single review, with
the smoke test — the only thing that caught any of it — rewritten in the same diff.

**`phase-20-complete` is therefore NOT tagged by this PR.**

## 9. ⚠️ Carried, not fixed

The Phase 19 dashboard defect is still open: two `EcomDemo Overview` stat panels reduce an
instantaneous rate with `lastNotNull`, so `Orders placed / min` reads `0.00` for an hour containing
34 orders and `Failed checkouts` shows a **stale** figure as though it were current. Agreed plan is
its own `fix/dashboard-stat-reducers` branch. Full entry in `docs/decisions.md`.

Also open, and new: a released reservation whose HTTP call fails is logged and swallowed, so stock
can stay reserved for an order that never existed. Nothing reconciles it yet.

## 10. Environment left behind

Docker Desktop running; eleven containers up and healthy. `ecomdemo` at Flyway **V13**,
`inventory` at **V2**. `kafka-ui` is behind the `tools` profile (`docker compose --profile tools up`).
Prometheus scrapes both services as one job, so `sum by (service) (...)` gives one row each.
