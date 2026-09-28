# Phase 24 Test Report: Distributed Transactions (Saga)

- **Date:** 2026-09-28
- **Branch:** `feature/phase-24-saga`
- **Machine:** the WSL2 workstation. Every result below was produced by Claude Code on that machine.
- **Toolchain:** Spring Boot 4.1.1, Spring Framework 7.0.9, Spring Kafka (Boot-managed), PostgreSQL 18,
  JDK 21, Docker 29.8.0. **No new third-party dependencies**; one new library module (`outbox`) and
  one new service (`payment-service`).
- **Result:** ✅ green. `./mvnw clean verify` BUILD SUCCESS (**524 tests**, 0 failed, 0 skipped);
  smoke **361 passed / 0 failed / 0 skipped** on three cold runs of the final code (runs 9–11 in §4). Two
  earlier cold runs each failed; both were test-side defects, fixed in the script and reported in §4.

## 1. Full regression

`./mvnw clean verify`, BUILD SUCCESS, **524 tests, 0 failed, 0 skipped** (483 before this phase):

    common                12               ecomdemo-outbox       39            (new module)
    inventory-service     43               payment-service        9            (new service)
    catalog-service       41 + 12 ITs      customer-service      43 +  8 ITs
    notification-service  10 +  4 ITs      gateway-service        7 + 15 ITs
    ecomdemo-app         226 + 55 ITs
    unit 430, integration 94. Stack up while it ran (allowed on this machine).

**New tests (41):**

| Where | Tests | What they prove |
|---|---|---|
| `outbox` | `OutboxRoutesTest` (3), `ProcessedEventsTest` (2), `OutboxPackageRegistrarTest` (3) | routing by event type; the idempotent claim; the JPA package is added only when needed |
| inventory | `InventorySagaTest` (9, **real PostgreSQL + Flyway V3**) | all-or-nothing reservation; rejection reason; duplicate lines summed; redelivery; **two orders racing for the last unit → exactly one reserved** (row locks); compensation releases once, even for a *different* event |
| payment | `PaymentServiceTest` (5, **real PostgreSQL**), `PaymentPropertiesTest` (2), `PaymentSecurityTest` (2) | complete / decline / limit is inclusive / redelivery / never two charges per order; no business API |
| app | `OrderSagaHandlerTest` (5), `OrderRepositoryTest` +3, `OrderControllerTest` +2, `OrderApiIT` +1, `OutboxRelayKafkaIT` +1, `FlywayMigrationTest` +2 | confirm / cancel ×2 / redelivery / late reply ignored; the conditional UPDATE; the status endpoint; **decline → CANCELLED + stock restored, over the real Kafka container**; OrderPlaced only after confirmation; V18, V19 |

**Tests changed because the behaviour changed on purpose.** Every one was rewritten to assert the new
contract. None was deleted, disabled, or weakened to get a green build.

- **Status `PLACED` → `PENDING`** in 6 files (checkout no longer decides the outcome).
- `OrderPlacementServiceTest`: `verify(inventory).reserve(...)` per line → the lines in
  `OrderCreatedEvent`, plus `never().reserve/release`. "appends OrderPlaced" → "appends OrderCreated
  and **not** OrderPlaced".
- `ConcurrentCheckoutTest.aRollbackAfterReservingReleasesEveryLine` → **`aLateFailureLeavesNothingToCompensate`**.
  The HTTP reservation and its after-rollback release no longer exist. The test now fails checkout at
  its latest point (the outbox write) and asserts the stronger guarantee that replaced the
  compensation: no order, no `OrderCreated`, cart restored, nothing reserved or released.
- `PlaceOrderFlowTest`: the `reserve` request became a **row**, the `OrderCreated` payload with the lines.
- `OrderApiIT`, `SalesReportJobIT`: wait for the saga (via `FakeSagaParticipants` over the real Kafka
  container) before asserting stock or sales. `SalesReportJobIT` also asserts that a **cancelled**
  order is not a sale.
- `KafkaTemplateWiringTest`, `OutboxObservationConfigTest`, and the moved outbox unit tests follow the
  classes into the `outbox` module; the sales-report case uses a stand-in job there.
- `ContainerMemoryBudgetTest`: seven JVM services (was six). `OpenApiDocumentationTest`: the status path.

## 2. Phase acceptance: the Done-when item

**Done when: success and failure scenarios both end consistently, verified by an end-to-end test.**
✅ Verified by the smoke test's new **Saga** section, against the real 20-container stack, checking
**all three databases**:

    Saga (distributed transactions)
      PASS  payment-service is ready
      PASS  and has no business API: a payment cannot be asked for, only caused by an event
      PASS  the saga's five topics and their dead-letter topics exist
      PASS  a normal checkout is accepted (201)
      PASS  as PENDING: the saga decides the rest
      PASS  the saga ends CONFIRMED
      PASS  the status endpoint gives no reason for a confirmed order
      PASS  and says when it was decided
      PASS  the stock was taken: the catalogue converges on 3
      PASS  payment-service kept a COMPLETED payment for the order total
      PASS  inventory-service holds its reservation as RESERVED
      PASS  and only a CONFIRMED order is announced: the shopper is thanked
      PASS  a checkout over the payment limit is ALSO accepted - nobody knows yet
      PASS  the saga ends CANCELLED
      PASS  and the status says why
      PASS  payment-service kept the decline, with its reason
      PASS  inventory COMPENSATED: the reservation is RELEASED
      PASS  the stock is restored: the catalogue converges back on 5
      PASS  no OrderPlaced was published for it
      PASS  so nobody was thanked for an order that did not happen
      PASS  the cancelled order is still in the shopper's history, as CANCELLED
      PASS  one unit, two buyers: exactly one CONFIRMED (CANCELLED,CONFIRMED)
      PASS  and the stock is 0, never -1
      PASS  the loser was cancelled by inventory, for stock
      PASS  the saga probe products are cleaned up

Also at the app level, over the real Kafka container: `OrderApiIT.declinedPaymentCompensates` and
`OutboxRelayKafkaIT.orderPlacedFollowsConfirmation`.

| Checklist item | Evidence |
|---|---|
| A new mock `payment-service` | module + compose (8086, payment-db 5437); smoke: ready, no business API; `PaymentServiceTest` |
| OrderCreated (PENDING) → StockReserved/Rejected → PaymentCompleted/Failed → CONFIRMED/CANCELLED | smoke §Saga (all three endings); unit tests per service |
| Compensation that releases stock when payment fails | smoke: reservation RELEASED, stock back to 5; `InventorySagaTest.PaymentFailed` |
| Outbox publishers and idempotent consumers throughout | shared `outbox` module in app, inventory, payment; redelivery tests in all three |
| An order status endpoint | `GET /api/orders/{id}/status`; `OrderControllerTest`, smoke |
| The orchestration alternative documented | `docs/architecture/saga.md` (comparison table), README |

The trace now follows the saga too: the smoke test's tracing section finds the checkout's trace with
spans from **5 services**, including inventory's and payment's **consumer** spans and the order
service's confirmation.

## 3. Found in this round, and fixed

- **A `topic` column, reverted.** The first draft of the library stored each row's topic. That undid
  Phase 18's recorded decision (a topic rename should not be a migration), so the mapping became an
  `OutboxRoutes` bean per service instead.
- **Every outbox repository found twice.** `@EnableOutbox`'s first version added `com.ecomdemo.outbox`
  for JPA unconditionally; ecomdemo-app's own package already contains it, and 20 app tests failed to
  start with `BeanDefinitionOverrideException`. Fixed with a registrar that adds the package only when
  the application's does not cover it (`OutboxPackageRegistrarTest`).
- **Jackson 2 was on the app's classpath only by accident** (through springdoc). The outbox payload
  mapper needs it and `jsr310`, so the library now declares both.
- **The relay relied on a neighbour's `@EnableScheduling`** (the batch scheduler). The library now
  enables scheduling itself; in inventory and payment nothing else would have.
- **Two `KafkaTemplate` candidates.** `SagaListenerErrors` first borrowed the service's template. A
  test's mock made two, and the context refused to start. It now builds its own raw-bytes and JSON
  dead-letter producers.
- **A stubbed spy ran `MANDATORY`.** Stubbing the `OutboxWriter` spy through Spring's proxy ran the
  transaction check before Mockito saw the call. It is now stubbed on the proxy's target
  (`AopTestUtils`).
- **The sales report job runs once per date**, so a second IT could not run today's report. The
  cancelled-order check moved into the existing report test.

## 4. ⚠️ Smoke runs, all of them

| Run | Stack | Result |
|---|---|---|
| 1 (not counted) | cold | **Invalid**: the script file was edited while bash was executing it, and bash reads a script as it runs. It stopped with a syntax error. Its two real failures were the Flyway checks, which still expected V1–V17. Since then every run executes a **copy** of the script |
| 2 | warm, same stack | **361 / 0 / 0** |
| 3, 4 | cold | **361 / 0 / 0** each |
| 5 | cold | 359 / **2**: the happy path's order ended **CANCELLED**. Earlier runs had sold out the cheap products, so "first product with stock ≥ 2" was a 9,499.00 SSD, and two of them exceed the 10,000.00 payment limit. The saga behaved correctly: it cancelled and returned the stock (9 → 9). Fixed in the script: every product picked for an order that must succeed is now filtered to price × quantity ≤ the limit |
| 6, 7 | cold | **361 / 0 / 0** each |
| 8 | cold | 360 / **1**: "the order service's confirmation is in it". notification-service's consumer span reached Tempo before the order service's own consumer span, which happened seconds *earlier* but was still in the app's 5-second export batch. Tempo showed it moments later (every checkout trace checked afterwards had all five hops). Fixed in the script: the poll now waits for every hop the checks assert |
| 9, 10, 11 | cold, **final code** | **361 / 0 / 0** each |

(Run numbers here count every run of this phase; the scripts' own labels were 1–3, 4–6 and 7–9 per batch.)

Service logs of the final stack: the only ERROR lines come from the smoke test's deliberate failures.
The order service's two are from the planned Kafka outage (an `orders.created` send fails and stays in
the outbox until Kafka is back). notification-service's two are the deliberate poison message going to
its dead-letter topic. inventory-, payment-, catalog-, customer- and gateway-service logged none.

A cold run is `docker compose down` then `docker compose up --build --wait`.

## 5. Environment

Twenty containers (payment-service and payment-db are new). Memory after the final cold smoke run: **3720 MiB** in total. The seven
JVMs used 295–500 MiB each, every one at or under half its limit (the app 500 of 1024; payment-service
355 of 768). payment-db used 45 MiB. A cold `docker compose up --build --wait` took 32–34 s, and a cold
smoke run about 2 minutes.

## 6. Manual steps for you

1. **Watch one saga end both ways** (⚠️ what Grafana *shows* is not checked automatically):
   `docker compose up --build --wait`, log in as a customer, and check out one cheap item and then
   2 × a product priced 6,000.00 or more. Poll `GET /api/orders/{id}/status`: CONFIRMED, then CANCELLED
   with "Payment declined: …". In Grafana → Explore → **Tempo**, open the newest `gateway-service http
   post` trace. It continues through inventory-service and payment-service and back to the order service.
2. **Read `docs/architecture/saga.md`** and decide whether the orchestration comparison matches your
   understanding. It is the "documented alternative" the phase asked for.
