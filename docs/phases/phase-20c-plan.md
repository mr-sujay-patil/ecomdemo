# Phase 20c Plan: extract `catalog-service`

> Third PR of Phase 20. 20a prepared the split, 20b extracted `inventory-service`, this extracts the
> catalogue. Not approved until the user says so; nothing moves before then.

## 1. Evidence gathered before planning

**Who depends on `catalog` today** (from the generated module diagram, not from memory):

    Batch  -> Catalog          the CSV import creates and updates products
    Cache  -> Catalog          it caches catalogue DTOs
    Cart   -> Catalog          add-to-cart looks a product up to snapshot its name and price
    Catalog -> Inventoryclient a listing shows stock
    Catalog -> Sharedkernel

`order -> catalog` is already gone — 20a removed it, because checkout builds both the order line and
the reservation from the cart's snapshot. **Checkout will not call catalog-service at all.**

**Memory, measured just now with two services running** (not projected):

    ecomdemo-app            343 MiB / 768M cap
    ecomdemo-inventory-service  185 MiB / 384M cap
    kafka 396 · grafana 141 · loki 103 · alloy 79 · prometheus 61
    each PostgreSQL          ~18 MiB
    TOTAL                   1405 MiB against a 3916 MiB ceiling

A small service idles at **185 MiB, not the 296 MiB** 20a projected from the monolith. Adding
catalog-service plus its database costs roughly **+205 MiB → ~1.6 GB**. The memory risk 20a called
"the open risk" is not the binding constraint it looked like. All five services project to ~2.2 GB.

## 2. What moves

`com.ecomdemo.catalog` **and `com.ecomdemo.cache` together.** 20a established why: cache reads
catalog's DTOs and exists to cache the catalogue. It is not a shared facility; it belongs to the
service whose reads it accelerates. `ProductCacheEvictor` goes with it, which means **catalog-service
becomes the consumer of `inventory.stock-changed`**.

`catalog_db` gets the `product` table and its 10-row seed, Flyway starting again at **V1**.

## 3. The four decisions this phase turns on

### 3.1 Two seeds became three databases ⚠️
`inventory_db`'s V2 hard-codes product ids 1–10 to match the app's `V2__seed_products.sql`, and
`SeededStockAgreesWithTheCatalogueTest` compares the two files because no query can see both. That
test is inventory-service's, it reads paths, and the catalogue's path is about to change.
**Proposal:** move the product seed to `catalog-service`, update the one path in the test, and keep
the test where it is. It is already the right shape for this; it just needs the new location.

### 3.2 Who owns the inventory client? ⚠️ THE REAL QUESTION
A listing shows stock, so **catalog-service must call inventory-service** — and `InventoryGateway` /
`InventoryClient` currently live in `ecomdemo-app`. Three options:

| | Approach | Cost |
|---|---|---|
| **A (recommended)** | Move the client into `common`; both services use the parts they need | `common` gains a RestClient and knowledge of inventory's API shape |
| B | catalog-service writes its own client | Two copies of the 409 → `InsufficientStockException` mapping; a divergence would be a silent bug, not independence |
| C | The app aggregates: fetch catalogue, then stock, and merge | Two network calls on the hottest read, and the app re-acquires a responsibility it is shedding |

**Recommend A.** 20b deliberately duplicated the *event payload* record, and that reasoning was
right — a message contract should not be a shared jar. A *client adapter* is different: both callers
want byte-identical behaviour against one API, and the failure mode of divergence is a bug.

### 3.3 Cart and batch become network callers
`CartService.addItem` looks a product up to snapshot name and price — that is an HTTP call now, on a
path a shopper waits for. `ProductUpsertWriter` and `ProductImportJobConfig` create and update
products, so the **CSV import becomes a distributed write** across catalog and inventory. The
original plan (§7) already predicted this and recommended tolerating a partial failure, documented,
rather than building a second outbox. **Proposal:** honour that, and record the gap.

### 3.4 The cache evictor needs its own container factory in its new home
20b's most expensive bug: a global `spring.json.value.default.type` made the stock event deserialise
as an `OrderPlacedEvent`, **with zero consumer lag** the whole time. `StockChangedListenerConfig`
moves with the evictor. `StockChangedListenerConfigTest` moves with it and will fail if it does not.

## 4. Order of work — each commit leaving `./mvnw clean verify` green

1. `catalog-service` module: pom, application class, `catalog_db` Flyway V1 (DDL + seed).
2. Move `com.ecomdemo.catalog` and `com.ecomdemo.cache` into it, with their tests.
3. Its REST API, its `SecurityConfig`, and its Alloy/Prometheus wiring.
4. The inventory client moves to `common` (decision 3.2) and catalog-service uses it.
5. A `CatalogGateway`/`CatalogClient` seam in `ecomdemo-app`; cart and batch change wiring, not logic.
6. Compose: `catalog-db` + `catalog-service`, 384M, health check, `depends_on`.
7. Smoke test across three services; full testing protocol; report; docs; PR.

## 5. What I expect to go wrong

- **The app's product endpoints.** `/api/products` is public and is what the smoke test drives. It
  has to keep working identically while the data moves — the split is not supposed to be visible.
- **`@PreAuthorize("hasRole('ADMIN')")` on product writes**, the exact trap 20b hit: once the caller
  is a service, the admin check has to stay at the edge.
- **Cache tests.** `CacheApiIT` and the eviction checks move to a service with a different context.
- **Three Flyway histories** to keep straight, and `V13__drop_product_stock.sql` has a sibling now:
  the app needs a migration dropping `product`.

## 6. Not in scope

customer-service, notification-service, and turning `ecomdemo-app` into order-service. Those are 20d.
**`phase-20-complete` is not tagged by this PR either.**
