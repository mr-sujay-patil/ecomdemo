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
