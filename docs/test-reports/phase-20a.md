# Phase 20a Test Report: Preparing the Microservices Split

- **Date:** 2026-09-23
- **Branch:** `feature/phase-20-microservices`
- **Toolchain:** Spring Boot 4.1.1, Spring Modulith 1.4.1, PostgreSQL 18.6, Redis 8, Apache Kafka
  4.2.1, Loki 3.7.8, Alloy v1.19.2, Flyway V12, Testcontainers 2.0.5, JDK 21, Docker 29.7.2,
  Compose v5.4.0
- **Result:** ✅ green. `./mvnw clean verify` **334 + 83**, 0 failures, 0 skipped; smoke test
  **270 passed**, 0 failed, 0 skipped.
- **Scope:** ⚠️ **This is the first half of Phase 20.** The services are not extracted yet. See §7.

## 0. Why this is half a phase

Phase 20 splits the application into five services. Planned out and measured, that is a great deal
larger than any phase before it, and it divides cleanly in two:

- **20a (this PR)** — the changes that make the split *possible*: two data changes that had to
  happen while the application was still one deployable, and the build becoming a reactor.
- **20b** — extracting the five services, five databases, HTTP clients, JWT per service, the
  Compose topology, and the smoke test rebuilt against per-service ports.

The division is not arbitrary. Everything in 20a ends with **the same 270 smoke checks passing,
unchanged**, which is only possible while there is still one application to run them against. 20b
rewrites that script against different ports — so the safety net is replaced at the same moment
the largest changes land, and putting the riskiest data work on the near side of that line was the
whole point of the sequencing.

**`phase-20-complete` is deliberately NOT tagged by this PR.** Phase 20 is not complete.

## 1. Full regression — `./mvnw clean verify`

    [INFO] Tests run: 334, Failures: 0, Errors: 0, Skipped: 0     <- Surefire (was 319)
    [INFO] Tests run: 83,  Failures: 0, Errors: 0, Skipped: 0     <- Failsafe (was 82)
    [INFO] BUILD SUCCESS

15 new unit tests (16 in `InventoryServiceTest`, less the ones that moved out of other classes)
and one new integration test. Nothing skipped, nothing disabled.

## 2. The number that matters

    scripts/smoke-test.sh  ->  270 passed, 0 failed, 0 skipped

The same 270 as Phase 19, after **two migrations, a table split, a foreign key removed and the
build restructured into a reactor**. Three checks changed their expected value — two migration
lists and the artifact name — and no check was added or removed. Nothing a user can observe
changed except the one thing §4 is about.

## 3. `product_stock`, and the dependency that inverted

Phase 19 separated catalogue from inventory as modules and left them sharing the `product` table,
recording in `StockMutationRulesTest` that *"Phase 20 is where that question belongs"*. Two
services cannot share a column, so V11 splits it.

The interesting result was not the migration. **`InventoryService` stopped needing the catalogue
at all.** It used to hold a `Product`, mutate a field and save through `ProductService`; it now
deals in product ids:

    before:   inventory -> catalog
    after:    catalog   -> inventory

That is the shape the services must have — an inventory-service will not have a catalogue to
depend on — and splitting the table is what made the code admit it. The generated diagram caught
it exactly:

    -Rel(Inventory, Catalog, "uses")
    +Rel(Catalog, Inventory, "uses")

which is Phase 19's determinism fix earning its keep: the claim was that a PR moving a dependency
would show the moved arrow in its diff.

**`StockMutationRulesTest` now names one module.** Phase 19's version had to permit `catalog` as
well, because an entity's owner cannot be denied a column on it, and said so at length. The
exemption is gone with the column.

Three decisions inside the migration worth stating:

- **No foreign key** on `product_stock.product_id`. Inside one database it would work and be free;
  two migrations from now these tables are in different databases, where it cannot exist. Adding
  one would buy a few commits of integrity in exchange for teaching the code to rely on a
  guarantee about to be withdrawn. A missing row reads as **zero**.
- **The optimistic lock moved, and sharpened.** On the product row `@Version` guarded every column
  at once, so a checkout collided with an administrator editing a description — two writes with
  nothing to say to each other. It now sits on the row that actually contends.
  `ConcurrentCheckoutTest` still asserts the collision that must happen.
- **`requireAvailable` takes the product NAME**, because this module can no longer look one up.
  The first small tax of the split, and the honest one: the alternative is an inventory service
  calling the catalogue back to build an error message.

## 4. ❗ The one behaviour that changed

`cart_item` held a real foreign key to `product` and `CartItem` held `@ManyToOne Product`. The
cart goes to order-service and the product to catalog-service, so neither survives.

**Before:** `lineTotal()` multiplied quantity by `product.getPrice()`, read through the
association on every load. A cart always reflected *today's* catalogue — edit a price and every
existing cart silently repriced.

**After:** the cart reflects the catalogue **as at the moment the line was added**.

That is a real change and it is not strictly better; it is the trade a distributed system makes. A
self-repricing cart needs a call to the catalogue on the hottest read there is. It is arguably the
more honest behaviour too: the price a shopper was shown is the price they expect at checkout.
**What an order is charged did not change** — `order_item` has snapshotted price at checkout since
Phase 6.

Nothing in the 270 smoke checks depended on repricing, which means nothing was testing it. A
deliberate semantic change with no test is one that silently reverts, so `CartApiIT` now changes a
price behind an existing cart and asserts the line and the total do not move.

**A second dependency disappeared, unplanned.** Checkout builds the order line *and* the
reservation from the cart's snapshot, so it never reads a product:

    order -> catalog    GONE

order-service will place an order **without calling catalog-service at all**. No network hop on
the checkout path, and the price charged is the price the shopper was shown. This fell out of
removing the foreign key; it was not in the plan.

Removing the key also **removed a query**: `CartRepository`'s `JOIN FETCH` had a second fetch for
`i.product` to avoid N+1 on the catalogue, and there is no per-line lookup left to make.

## 5. The build is a reactor

One POM producing one jar becomes a parent that builds nothing, a `common` library, and
`ecomdemo-app` — still the whole application, still one deployable.

**`common` depends on no other module of this project**, which is the property that makes it safe
underneath everything else. It was not free: `shared` imported `NO_TOKEN` from `security`,
survivable inside one jar and a cycle Maven cannot resolve between two. The constant moved to
`shared.AuthMessages` — the same answer Phase 19 gave for the JWT claim names, that a string two
modules must agree on is a contract and a contract belongs to the shared kernel.

**`cache` did NOT go into `common`**, and the dependency graph is why: it reads catalog's DTOs and
listens for inventory's stock event, so a `common` holding it would have to depend on both. It
stays in `ecomdemo-app` and belongs to catalog-service in 20b.

Every plugin moved into `<pluginManagement>`, so a module opts in by naming it. That matters most
for `spring-boot-maven-plugin`: repackaging is right for a deployable and a build failure for a
library with no main class.

## 6. Three things that broke, and what they teach

**`mvn test-compile` reported BUILD SUCCESS against stale test classes.** After a signature change,
`TestData` had not been recompiled, so tests that could not possibly compile appeared to.
`mvn clean test-compile` then showed four broken files. Incremental compilation is not a safe
check that a refactor is complete.

**Six tests began reading files one directory too deep.** Maven sets a test's working directory to
its MODULE, and until this phase the module was the repository — so every
`Path.of("compose.yaml")` broke at once, in tests reading files nobody had touched. They now go
through a `ProjectRoot` helper that walks up to find the marker. Writing `"../compose.yaml"` would
have encoded the module's current depth into every call site, and 20b changes that depth again
with every extraction.

**`AuthApiIT` caught a user-facing regression.** Moving `INVALID_TOKEN` between classes, its
wording was also changed. Moving a constant is a refactor; rewording it is a change to what a
caller sees — and doing both in one commit is exactly how the second happens by accident. Original
wording restored.

`/actuator/info` now reports `ecomdemo-app` rather than `ecomdemo`, because the deployable is a
module. The smoke check asserts the new value: that endpoint exists to say which build is running,
so the day the answer changes it should say so.

## 7. ⚠️ What 20b still has to do

- Extract `catalog-service`, `inventory-service`, `customer-service`, `notification-service`;
  `order-service` is what remains.
- A database per service, each with its own Flyway history.
- `RestClient` between services where the answer is needed now; Kafka where it is not. The outbox
  stays in order-service only.
- JWT validation in every service; issuing stays in customer-service.
- Compose: ~14 containers, a **384M cap per service** and `kafka-ui` behind a profile.
- The smoke test rebuilt against per-service ports.

**The memory ceiling is the open risk.** Docker Desktop is capped at **3.8 GB** on this 8 GB host,
and at idle a JVM costs **296 MiB** against PostgreSQL's **31 MiB** — which is why the plan keeps
database-per-service (five databases cost ~125 MiB more than one) and caps the JVMs instead.
Projection is ~2.0–2.4 GB. That should fit; "should" is doing real work in that sentence, and a
stack that will not start means no smoke verification at all.

## 8. ⚠️ Carried, not fixed

The Phase 19 dashboard defect is still open: two `EcomDemo Overview` stat panels reduce an
*instantaneous* rate with `lastNotNull` while the two beside them aggregate over the range, so
`Orders placed / min` reads `0.00` for an hour containing 34 orders and `Failed checkouts` shows a
**stale** figure as though it were current. Agreed plan is its own `fix/dashboard-stat-reducers`
branch. Full entry in `docs/decisions.md`.

## 9. Environment left behind

Docker Desktop running, nine containers up and healthy, schema **V12**. The SonarQube stack is
stopped. Two Grafana traps that cost time in Phase 19 and will again: checking out a branch while
Grafana runs replaces the bind-mounted provisioning directory's inode (`docker compose up -d
--force-recreate grafana`), and the admin password persists on the `grafana-data` volume while
repeated 401s trip a five-minute brute-force lockout.
