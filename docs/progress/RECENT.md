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

## Phase 20b: Microservices Split - EXTRACTION, first service (tag: NONE - see below, PR #TBD)
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

## Phase 20a: Microservices Split - PREPARATION (tag: NONE - see below, PR #26)
**What exists now:** Still ONE deployable, but every precondition for splitting it is in place.
`product_stock` is its own table, `cart_item` snapshots the product instead of pointing at it, and
the build is a Maven reactor (parent + `common` library + `ecomdemo-app`). 417 tests (334 + 83),
smoke test **270 - UNCHANGED**, schema **V12**. Phase 20 is HALF DONE: 20b extracts the services.
**DO NOT TAG `phase-20-complete` until 20b merges.**
**Key code:** `inventory.ProductStock` + `ProductStockRepository`; `InventoryService` now deals in
product IDS and no longer sees a `Product`. `CartItem` holds productId/productName/unitPrice.
`shared.AuthMessages` (the 401 wording, moved out of `security` to break a Maven cycle).
`support.ProjectRoot` for tests that read repo files. `batch.ImportedProduct` carries the stock
level to the writer, because a processor cannot set stock on an unsaved product.
**Config & infrastructure:** V11 splits product_stock (no FK - the tables are about to be in
different databases; a missing row reads as zero). V12 adds cart_item.product_name/unit_price and
drops fk_cart_item_product. Dockerfile copies both module poms and takes the jar from
`ecomdemo-app/target/`. CI report paths moved under `ecomdemo-app/`.
**Tests:** `InventoryServiceTest` (16, against a real database - the stock arithmetic and the
stock-changed event moved here from OrderPlacementServiceTest and ProductServiceTest). New
`CartApiIT` case pinning that a cart keeps the price it was added at.
**Gotchas:** (1) TWO DEPENDENCIES INVERTED OR VANISHED: `inventory -> catalog` became
`catalog -> inventory`, and `order -> catalog` is GONE - checkout reads no product at all, so
order-service will not call catalog-service. (2) A cart now reflects the catalogue as at
add-to-cart time, not today's; what an order is CHARGED is unchanged. (3) `mvn test-compile` can
report success against STALE test classes - use `clean`. (4) Maven sets a test's working directory
to its MODULE, which broke six file-reading tests at once. (5) Moving a constant is a refactor;
REWORDING it is user-facing - AuthApiIT caught that. (6) `cache` cannot go in `common`: it reads
catalog DTOs and listens for inventory events.
**Follow-ups (20b):** extract the five services, a database each, RestClient between them, JWT
validation per service, compose at ~14 containers with a 384M cap per service and kafka-ui behind
a profile, and the smoke test rebuilt against per-service ports. MEMORY IS THE OPEN RISK: Docker
Desktop is capped at 3.8 GB, a JVM idles at 296 MiB and PostgreSQL at 31, projection ~2.0-2.4 GB.
