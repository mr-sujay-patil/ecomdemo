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
