# Phase 20c Test Report: Extracting catalog-service

- **Date:** 2026-09-25
- **Branch:** `feature/phase-20c-catalog-service`
- **Toolchain:** Spring Boot 4.1.1, Spring Modulith 1.4.1, PostgreSQL 18.6, Redis 8, Apache Kafka
  4.2.1, Loki 3.7.8, Alloy v1.19.2, Testcontainers 2.0.5, JDK 21, Docker 29.8.0
- **Result:** ✅ green. `./mvnw clean verify` **5 + 34 + 41 + 281 + 65**, 0 failures, 0 skipped;
  smoke test **270 passed / 0 failed** on a cold catalogue, **271** on the second run.
- **Scope:** ⚠️ the third PR of Phase 20. Two of five services remain. **`phase-20-complete` is NOT
  tagged.**

## 0. What this delivers

`catalog-service` is a separate deployable with its own PostgreSQL database, its own Flyway history
and its own filter chain. **The Redis cache moved with it**, because what it caches is the
catalogue. Twelve containers, three services, three databases.

The application keeps the public `/api/products` and forwards to it, so nothing a shopper or the
smoke test can see has changed.

## 1. Full regression

    [INFO] Tests run: 5,   Failures: 0     <- ecomdemo-common
    [INFO] Tests run: 34,  Failures: 0     <- inventory-service
    [INFO] Tests run: 41,  Failures: 0     <- catalog-service (new)
    [INFO] Tests run: 281, Failures: 0     <- ecomdemo-app (was 317)
    [INFO] Tests run: 65,  Failures: 0     <- Failsafe (was 81)
    [INFO] BUILD SUCCESS

The app's counts fall because tests moved to the service that now owns what they assert. §4 accounts
for every one of them.

## 2. The number that matters

    scripts/smoke-test.sh  ->  270 passed, 0 failed   (cold catalogue)
                           ->  271 passed, 0 failed   (second run)

The difference is the persistence probe: against a `catalog_db` it has never seen it correctly
reports "first run", and on the next pass it verifies the probe survived. Four checks changed; none
was removed or weakened.

## 3. ❗ Two failures that passed every test and still broke the container

The theme of 20b repeated, in two new variations. Both are worth more than the fixes.

**① `RestClient.Builder` was a test-scope dependency.** `spring-boot-restclient` was added at
`test` scope to satisfy `TestRestTemplate`'s auto-configuration. Every test passed — because on the
*test* classpath the auto-configured `RestClient.Builder` bean existed. The container then refused
to start:

    No qualifying bean of type 'org.springframework.web.client.RestClient$Builder'

The builder is auto-configured by that module and nowhere else, and catalog-service calls
inventory-service on every listing. **A test classpath richer than the runtime classpath is a test
suite proving something about a program that will not be deployed.**

**② The Dockerfile has a list of modules in it.** It copies module POMs one line at a time, which is
what gives the dependency layer its cache — so adding `catalog-service` to `pom.xml` and not to the
Dockerfile produced `Child module /build/catalog-service does not exist`, minutes into
`docker compose build`, with the entire suite already green. `DockerfileCoversEveryModuleTest` now
reads both lists instead of trusting anyone to remember. Mutation-checked.

**③ A fake that lied by omission**, caught by four integration tests rather than by review. The real
catalog-service calls inventory-service on every write *and asks it for the stock figure on every
read*. `InMemoryCatalog` stored the number given at creation and returned it for ever, so a product
created with 5 still read 5 after two were sold. **A fake has to mirror the collaboration, not just
the data.**

## 4. Where every moved test went

Nothing was deleted to make this compile. Each relocation left a comment at the old site naming the
new one, and each claim was verified to hold in its new home *before* the old one was removed.

| Claim | From | To |
|---|---|---|
| A re-import updates rather than duplicating | `ProductImportProcessorTest` (app) | `ProductServiceTest` (catalog-service) — name resolution happens there now |
| Public access rules: anonymous reads, ADMIN writes, standard 403 body | `ProductControllerTest` (catalog-service) | `ProductProxyAccessTest` (app) — where a human's token actually arrives |
| V2 seed, V3 categories, V4 version column, V8 index | `FlywayMigrationTest` (app) | `CatalogSchemaTest` (catalog-service), against a real PostgreSQL with Flyway on |
| Checkout ignores the cache; a sale evicts; oversell guard with a warm cache | `CacheApiIT` | the smoke test — the only place three services run at once |

Two assertions were **dropped rather than moved**, and both are named in a comment where they used
to be: `"and never reaches the database, so a bad row costs no query"` became true by construction
once the processor lost its only collaborator, and a test that can only pass is not worth a line.

## 5. ❗ What is genuinely worse now

**The CSV import is a distributed write with no shared transaction.** It used to say, in a comment
written in 20a: *"Both writes are in this chunk's transaction, so a failure rolls the pair back
together — which is the LAST time that will be true."* This is that moment. The catalogue write and
the stock write are two HTTP calls to two services; if the first succeeds and the second fails, the
import has created products with no stock — visible, unbuyable, and nothing rolls them back.

Tolerated deliberately. A second outbox is a phase of its own, and a saga across ten thousand rows
is more machinery than the problem deserves. What makes it tolerable is that **the import is
restartable and idempotent**: re-running sets the same levels again, so the repair is to run it
again rather than to reconcile by hand.

**Adding to a cart is now a network call** on a path a shopper waits for. A missing product still
surfaces as a clean 404, because the client maps the remote 404 back to `NotFoundException`.

**Product ids are only unique within catalog_db.** The race check counted every order holding the
probe product and found two — an order from an earlier run held the same id, because the catalogue
was re-provisioned while the application's orders persisted. The application is unaffected (an order
snapshots what it charged), but anything counting by a foreign id has to scope its window.

## 6. Decisions

**The clients live in `common` under `com.ecomdemo.clients`**, as approved. Two package choices were
forced by running the thing:

- The client could **not** be `com.ecomdemo.inventory`: inventory-service scans that package, so it
  would have built a `RestClient` pointing at itself.
- `ServiceIdentityConfig` could **not** live in `com.ecomdemo.jwt`. Every service scans that, because
  every service must *verify* tokens — but only a service that *calls* another needs to sign one, and
  inventory-service refused to start when asked for a `JwtEncoder` it has no reason to own.
  **"Everyone verifies, only callers sign" is now enforced by which package the bean is in.**

**One `ServiceTokenProvider`, subject = `spring.application.name`.** A first draft gave each client
its own, which was wrong twice: two beans of one type made injection ambiguous, and both hard-coded
`ecomdemo-app`, so catalog-service calling inventory-service would have claimed to be the
application.

**`V14__drop_product.sql`.** Leaving the table would be worse than untidy: a stale query would still
work, still return rows, and be a frozen copy drifting further from the truth with every edit.

## 7. Memory: the 20a risk, measured

    app 274 MiB · catalog-service 183 · inventory-service 170
    kafka 414 · loki 179 · grafana 77 · alloy 68 · prometheus 45
    three databases ~16 MiB each
    TOTAL 1486 MiB against a 3916 MiB ceiling

20a projected a service at **296 MiB** by extrapolating from the monolith and called memory "the
open risk". Measured, a small service idles at **170–183 MiB**, and a third service plus its database
cost about 200 MiB. Five services project to roughly **1.9 GB**. The risk was real to flag and is not
the binding constraint it looked like.

## 8. ⚠️ What remains

`customer-service` (which issues tokens) and `notification-service`; `order-service` is what
`ecomdemo-app` becomes. That is **20d**, and the one-service-per-PR rhythm is earning its keep: this
phase produced three more container-only failures, and the tests written for them now fail
automatically for the next service.

## 9. ⚠️ Carried, not fixed

- The Phase 19 dashboard defect: two `EcomDemo Overview` stat panels reduce an instantaneous rate
  with `lastNotNull`. Agreed: its own `fix/dashboard-stat-reducers` branch.
- A failed compensating release still leaks a reservation; nothing reconciles it.
- HS256 with a shared secret: every service can mint as well as verify.

## 10. Environment left behind

Twelve containers up and healthy. `ecomdemo` at Flyway **V14**, `catalog` at **V2**, `inventory` at
**V2**. `kafka-ui` behind `--profile tools`; remember `docker compose --profile tools down` or the
network survives the stop.
