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
