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

## Phase 20d: Microservices Split - the last two services (tag: phase-20-complete, PRs #29-#32)
**What exists now:** FIVE deployables, sixteen containers, five databases. `customer-service` owns
`users` (customer_db, 8083/5435) and is the only issuer of user tokens; `notification-service` owns
`notification` + `processed_event` (notification_db, 8085/5436) and has no business API at all;
`catalog-service` and `inventory-service` as before; `ecomdemo-app` is order-service in all but name,
keeping only cart/orders/outbox/batch plus two public proxies. Smoke **275 passed / 0 failed** from a
COLD stack on `main`; **1459 MiB of 3916**. Verified and tagged 2026-09-26 at `f819d3e`.
**Key code:** `common/.../jwt/CurrentUser` (reads CLAIMS, no repository); `common/.../clients/customer/`;
`ecomdemo-app/.../identity/` (the auth + customer proxies); `customer-service/` and
`notification-service/` whole modules; V15 (snapshot + backfill username, drop the user FKs) and V16
(drop users/notification/processed_event).
**Config & infrastructure:** `CUSTOMER_BASE_URL`; customer-db 5435, notification-db 5436; 320M caps on
the two new services; Alloy and Prometheus cover all five; the Dockerfile copies five module poms.
**Tests:** OrderPlacedConsumerIT (publishes a MAP, not its own record), CustomerIntegrationTest (the one
base that CAN log in), ProductProxyAccessTest, EveryModuleWithIntegrationTestsRunsThemTest.
**❗ Gotchas - read `docs/test-reports/phase-20d.md` §0 first.** Failsafe was never bound in the four
extracted services, so their *IT tests had NOT RUN since 20b - `verify` printed BUILD SUCCESS the whole
time, because an unbound plugin reports nothing. Binding them found five real defects. Also: a
package-private `@BeforeEach` is not inherited across packages; the resource server needs its OWN
authenticationEntryPoint or a bad token returns an empty body; `TokenView` guessed its field names and
produced a 400 that blamed the caller for a response-side error; and the outbox relay stops the batch at
the first failure, so a warm-up row left pending during an outage starves the row under test.
**Follow-ups (not done):** rename `ecomdemo-app` to order-service (cosmetic, touches every image tag);
Phase 21's gateway replaces both proxies and removes the plaintext password from the app's memory; the
Phase 19 dashboard defect is now the oldest open item; a failed compensating release still leaks a
reservation; HS256 shared secret.

## Phase 20c: Microservices Split - catalog-service (tag: NONE - see below, PR #28)
**What exists now:** THREE deployables. `catalog-service` owns `product` in `catalog_db` (Flyway
V1-V2) and the Redis cache that serves it; `inventory-service` owns stock; `ecomdemo-app` is the
rest, plus the PUBLIC `/api/products` which forwards to catalog-service. 426 tests
(5 + 34 + 41 + 281 + 65), smoke **270 cold / 271 warm**, twelve containers, **1486 MiB of 3916**.
**TWO of five services remain. `phase-20-complete` is NOT tagged.**
**Key code:** `common/.../clients/` (CatalogGateway, InventoryGateway, ServiceIdentityConfig - the
clients and the identity a caller signs with); `catalog-service/` whole module;
`ecomdemo-app/.../catalog/ProductController` (the temporary public proxy, replaced by Phase 21's
gateway); `V14__drop_product.sql`.
**Config & infrastructure:** `catalog-db` (5434) + `catalog-service` (8081), 384M each;
`CATALOG_BASE_URL`; Alloy and Prometheus cover all three services; Dockerfile copies three module
poms and `DockerfileCoversEveryModuleTest` checks it does.
**Tests:** CatalogSchemaTest, ProductProxyAccessTest, DockerfileCoversEveryModuleTest,
CatalogIntegrationTest (base: containers + a SERVICE token, because this service has no login).
**Gotchas:** THREE more failures that passed every test and still broke the container -
`spring-boot-restclient` at TEST scope (the RestClient.Builder bean is auto-configured there, so the
test classpath had a bean the runtime did not); the Dockerfile's per-module COPY list going stale;
and `InMemoryCatalog` storing a stock number instead of asking inventory for it on every read, which
four ITs caught. Also: product ids are only unique within catalog_db, so anything counting by a
foreign id across a re-provision needs its window scoped - the smoke race check found two orders
holding "the same" product.
**Follow-ups (not done, out of scope):** extract customer-service and notification-service;
`order-service` is the residue. That is 20d. Still open: the CSV import's partial-failure window, a
failed compensating release leaking a reservation, the HS256 shared secret, and the Phase 19
dashboard defect.
