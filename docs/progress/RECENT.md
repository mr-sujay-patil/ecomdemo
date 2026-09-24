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

## Phase 20c: Microservices Split - catalog-service (tag: NONE - see below, PR #TBD)
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

## Phase 20b: Microservices Split - EXTRACTION, first service (tag: NONE - see below, PR #27, MERGED)
**What exists now:** TWO deployables. `inventory-service` owns stock in its own `inventory_db`
(Flyway V1-V2), exposes a REST API, publishes `inventory.stock-changed`, and validates JWTs with its
own filter chain. `ecomdemo-app` reaches it over HTTP behind `InventoryGateway`. 437 tests
(5 + 34 + 317 + 81), smoke test **270 - UNCHANGED**, eleven containers healthy.
**ONE of five services is extracted. Phase 20 is NOT complete and `phase-20-complete` is NOT tagged.**
**Key code:** `common/.../jwt/` (JwtKeyConfig, JwtAuthorities, ServiceTokenProvider, ServiceTokens -
validation is shared, ISSUING stays in the app); `inventory-service/` whole module;
`ecomdemo-app/.../inventory/` (InventoryGateway, InventoryClient, InventoryClientConfig);
`cache/StockChangedListenerConfig`; `order/internal/OrderPlacementService` (the saga).
**Config & infrastructure:** `inventory-db` (5433) + `inventory-service` (8082), 384M limit each;
`INVENTORY_BASE_URL`, `JWT_SECRET` shared by both services; `kafka-ui` behind `--profile tools`;
Prometheus scrapes both as ONE job so `sum by (service)` works; Alloy ships both new containers.
Dockerfile: `ARG MODULE` is NOT read by the build stage - that is what keeps one reactor build.
`common` publishes a **test-jar** (ProjectRoot).
**Tests:** InventorySecurityTest, InventoryApiValidationTest, SeededStockAgreesWithTheCatalogueTest,
ServiceTokenProviderTest, StockChangedListenerConfigTest,
LoggingStackConfigTest.alloyShipsEveryServiceThisRepositoryBuilds. All mutation-checked.
**Gotchas:** four failures got past `clean verify` - no filter chain (everything 401 while HEALTHY),
empty seed data (all stock zero, silently), a GLOBAL `spring.json.value.default.type` deserialising
the stock event as an OrderPlacedEvent **with zero consumer lag**, and Alloy shipping no logs for
the new containers. Cache eviction is now EVENTUALLY consistent (it crosses a broker); the smoke
check polls and reports the convergence time. `@PreAuthorize("hasRole('ADMIN')")` inside a service
called with a SERVICE token can only answer 403 - and was inert anyway without
`@EnableMethodSecurity`.
**Follow-ups (not done, out of scope):** extract catalog-service (takes `cache`), customer-service,
notification-service; `order-service` is the residue. **Recommended one service per PR** - see
`docs/test-reports/phase-20b.md` §8. Also open: a failed release call leaks a reservation, nothing
reconciles it; HS256 shared secret; the Phase 19 dashboard defect.
