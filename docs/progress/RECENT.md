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

## Phase 19: Modular Monolith (tag: phase-19-complete, PR #24 + follow-up #25)
**What exists now:** Fourteen named modules, each declaring in `package-info.java` exactly which
others it may depend on, enforced by `ModularityTest` - an undeclared import now fails the build
naming both ends. The graph is ACYCLIC. Every module keeps its published API at its package root
and everything else under `<module>.internal`. No schema change, no new endpoint, no new container,
and the smoke test is **270 checks, unchanged**, which is the phase's real result: ~90 files moved
and no behaviour did. 401 tests (319 + 82). Schema still **V10**.
**Key code:** `catalog` (was `product`) and a new `inventory` that owns stock movement;
`shared` (was `common`); `customer.CurrentUser` (was in `security`) and `shared.TokenClaims` (was
`JwtConfig.Claims`) - those two moves broke the application's only dependency cycle. Three new
narrow APIs replaced cross-module repository access: `customer.UserDirectory`,
`catalog.ProductService.findFirstByName/saveAll`, `messaging.EventDeduplicator.claim`.
**Config & infrastructure:** `spring-modulith-api` at COMPILE scope (annotations go on main
source), `spring-modulith-core` and `-docs` at TEST scope. Boot 4.1.1 does not manage Spring
Modulith and the GA line targets Boot 3.5 - keeping the runtime starter out is what makes that gap
safe. Nothing else changed.
**Tests:** `ModularityTest` (boundaries + regenerates `docs/modules/`), `StockMutationRulesTest`
(ArchUnit: who may write stock), `EventDeduplicatorTest` (the idempotency assertions, moved from
`NotificationServiceTest`). 23 test classes moved into `internal` test packages to follow the
classes they cover.
**Gotchas:** (1) `catalog` and `inventory` SHARE the `product` table and the `Product` entity - the
split is behavioural, so the ArchUnit rule permits BOTH modules to write stock; `ProductService.update`
is the admin's full replace and cannot call into inventory without a cycle. (2) `ModularityTest`
writes into `docs/modules/` on every run, so `./mvnw test` touches the working tree - deliberate,
so a stale diagram shows as an uncommitted change. (3) Moving a package-private class into
`internal` breaks its test unless the test moves too. (4) `Documenter` drops `logging` from the
overall diagram because it has no edges; cosmetic.
**Follow-ups (not done, out of scope):** split `product` and `product_stock` so the stock rule can
name one module (Phase 20, if the service split demands it); let `catalog` contribute its own cache
configuration so `cache` stops knowing products exist; `order -> cart` stays a direct call on
purpose, since `clearCart` is inside the checkout transaction.
