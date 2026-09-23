# Phase 20 Plan: Microservices Split

> Written before any code, at the user's request. `docs/phases/phase-20-microservices.md` is the
> spec; this is how it gets built and what it costs. **Awaiting approval.**

## 0. The constraint that shapes everything

Measured, not assumed:

| | |
|---|---|
| Host RAM | **8 GB** |
| Docker Desktop ceiling | **3.8 GB** |
| Current nine containers | **~1.15 GB** |

Per-container, measured at idle:

    app (JVM)  296 MiB      kafka-ui  242 MiB      kafka  241 MiB
    grafana    199 MiB      loki       59 MiB      alloy   42 MiB
    prometheus  32 MiB      db (PG)    31 MiB      redis    9 MiB

**PostgreSQL idles at 31 MiB. A JVM idles at 296 MiB.** Five databases cost ~125 MiB more than
one; five JVMs cost ~1.2 GB more than one. That single fact decides the architecture below, and it
reverses the deviation that was floated before anything was measured: collapsing to one database
with a schema per service would have saved a rounding error while giving up the phase's entire
subject. **Database-per-service stays.**

Projection:

    5 JVM services    ~1250-1500 MiB
    5 PostgreSQL        ~155 MiB
    infra unchanged     ~824 MiB
                      --------------
                        ~2.4 GB of 3.8 GB   (~63%)

Feasible, but only with the two measures in §6.

## 1. The five services

| Service | Port | Owns | From today's modules |
|---|---|---|---|
| `catalog-service` | 8081 | `product` (description, price) | `catalog` |
| `inventory-service` | 8082 | `product_stock` | `inventory` |
| `customer-service` | 8083 | `users` | `customer`, `security`, `auth` |
| `order-service` | 8084 | `cart`, `cart_item`, `orders`, `order_item`, `order_audit`, `outbox_event`, batch tables | `cart`, `order`, `batch`, `messaging` |
| `notification-service` | 8085 | `notification`, `processed_event` | `notification` |

`shared`, `logging`, `metrics`, `cache` become a **`common` library module** every service depends
on — not a service. They are code, not a deployable.

Maven becomes a multi-module reactor: a parent POM, one module per service, plus `common`.

## 2. ❗ The two real problems, and they are the whole risk

### 2.1 `catalog` and `inventory` share the `product` table

Phase 19 split the behaviour and deliberately left the table alone, recording the cost in
`StockMutationRulesTest`:

> *"Splitting `product` and `product_stock` is what would let the rule name a single module, and
> Phase 20 is where that question belongs."*

It is now due, because the two land in **different databases**. Plan:

- `catalog_db.product` keeps `id, name, description, category, version`.
- `inventory_db.product_stock` takes `product_id, quantity, version` — a new table in a new
  database, not a foreign key.
- `ProductResponse` no longer carries `stockQuantity` from its own row. The catalogue asks
  inventory for it, or the caller asks inventory directly (see §3).

**What this touches, and why it is the riskiest change in the phase:** the Phase 12 optimistic
lock on stock, the Phase 16 cache eviction keyed on a stock change, the CSV import that sets
stock, and the checkout reservation. All four were built against one row.

### 2.2 `CartItem` has a real foreign key to `product`

    CONSTRAINT fk_cart_item_product FOREIGN KEY (product_id) REFERENCES product (id)

and the entity holds `@ManyToOne Product product`. Cart goes to `order-service`; product goes to
`catalog-service`. **The FK and the association cannot survive.**

`CartItem` becomes what `OrderItem` already is — and `OrderItem` is the template, because it was
built this way in Phase 6 and needs no change at all:

    private Long productId;          // an id, not a reference
    private String productName;      // snapshotted
    private BigDecimal unitPrice;    // snapshotted

That is a genuine behaviour change to state plainly: today *"the cart reflects today's
catalogue"* because it reads the live product on every load. After the split it reflects the
catalogue **as at the moment the line was added**, unless the cart re-fetches. That is
eventual consistency arriving where it always arrives first — in a read that used to be a join.

## 3. Synchronous vs asynchronous

| Call | How | Why |
|---|---|---|
| order → catalog (price/name when adding to cart) | **HTTP**, `RestClient` | The answer is needed now, in the request |
| order → inventory (reserve at checkout) | **HTTP** | Must succeed or fail the checkout |
| order → notification | **Kafka**, via the existing outbox | Already asynchronous since Phase 18; nothing changes |
| catalog → inventory (stock for a listing) | **HTTP**, with a fallback | A catalogue that 500s because inventory is down is worse than one that omits a number |
| batch import → catalog + inventory | **HTTP** | Two writes, two services — see §7 |

**The outbox stays only in `order-service.`** It exists to make the order event durable with the
order; no other service has that problem yet. Replicating it into all five would be cargo cult.

## 4. JWT in every service

`customer-service` issues tokens (today's `auth`). Every service **validates** them with the same
`JWT_SECRET` — decoder configuration moves into `common`, the issuing side does not. No service
calls `customer-service` to check a token: that is the entire point of a signed token, and Phase 9
already made it stateless.

## 5. Migrations

Five Flyway histories, one per database, each starting at `V1` for its own tables. The existing
`V1`–`V10` are **not** carried forward wholesale — each service takes the DDL for the tables it
owns. The `product_stock` split is new DDL in `inventory_db`.

## 6. Making it fit

1. **Cap every service.** `deploy.resources.limits.memory: 384M` per service. The Dockerfile's
   `-XX:MaxRAMPercentage=75.0` is a percentage *of the container limit*, and `compose.yaml`
   already warns that without a limit it silently means 75% of the laptop. Five uncapped JVMs
   against a 3.8 GB cgroup is that mistake five times.
2. **`kafka-ui` behind a Compose profile.** 242 MiB for a dev convenience, opt-in with
   `--profile tools`.

Together: ~400 MiB back, taking the projection to roughly 2.0 GB.

## 7. What I expect to go wrong

- **The CSV import becomes a distributed write.** It creates a product AND sets its stock — now
  two services, no shared transaction. Honest options: import calls both and tolerates a partial
  failure (documented), or the import moves behind an event. Recommend the former for this phase,
  recorded as a known gap, because a second outbox is a phase of its own.
- **The smoke test is rebuilt while it is the safety net.** Phase 19's rule was "270 checks,
  unchanged" — that is what made a 90-file refactor safe. This phase explicitly rewrites the script
  against per-service ports. The net is re-woven mid-jump, and there is no way around it.
- **`ConcurrentCheckoutTest`** asserts two threads racing for the last unit against one database.
  Stock moves to another service over HTTP; that test has to be rethought, not deleted.
- **Startup ordering.** Five services, five databases, Kafka. `depends_on: service_healthy`
  everywhere, and the smoke test's readiness loop needs to wait for all five ports.

## 8. Order of work, each step leaving the build green

1. Maven multi-module skeleton: parent + `common`, monolith still runs as one deployable.
2. Split `product` / `product_stock` **inside the monolith**, with migrations and the four
   touchpoints fixed. Smoke test still 270. *This is the risky change, done while the net holds.*
3. `CartItem` drops its association, snapshots name and price. Smoke test still 270.
4. Extract `catalog-service` and `inventory-service`; order talks HTTP.
5. Extract `customer-service`, then `notification-service`; `order-service` is what remains.
6. Compose: five services, five databases, profiles, limits.
7. Rebuild the smoke test against per-service ports.
8. Testing protocol in full, test report, README, decisions, PR.

Steps 2 and 3 are deliberately done **before** anything is extracted, so the hardest data changes
happen while the full 270-check suite still applies end to end.
