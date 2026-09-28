# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-28
- **Phase:** 24 — Distributed Transactions (Saga pattern, choreography over Kafka)
- **Branch:** feature/phase-24-saga (cut from `main` at `a2d5b8c`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 23 merge verification — PASSED, `phase-23-complete` TAGGED at `a2d5b8c`
PR #37 merged as a merge commit (2 parents); 0 missing commits, 0 diffs, branch alive; CI on `main`
green; `verify` on `main` BUILD SUCCESS (483 tests, 0 failed/skipped, stack down); cold smoke
**327/0/0** (this machine).

## Checklist (from the phase file's "What you'll implement", split into steps)
- [x] 1. `outbox` Maven module (`com.ecomdemo.outbox`): the app's outbox moved + generalised
      (`OutboxRoutes` bean per service, NOT a topic column - keeps Phase 18's decision),
      `ProcessedEvents` claim, `SagaListenerErrors` (blocking retries → `-dlt`), `@EnableOutbox`.
      App on it with V18 (`processed_event`); verify green, 492 tests
- [x] 2. inventory-service: outbox + processed_event + `stock_reservation` (V3); consumes
      `orders.created` → StockReserved/StockRejected (all-or-nothing, rows locked FOR UPDATE by id);
      consumes `payments.failed` → `releaseForOrder` (compensation). InventorySagaTest 9/9 (Postgres)
- [x] 3. New mock `payment-service` (+ payment-db, host ports 8086 / 5437): consumes
      `inventory.stock-reserved` → PaymentCompleted/PaymentFailed (declines amount > limit);
      Dockerfile, compose, Prometheus, Alloy wired (CI builds the reactor, nothing to add). 9 tests
- [x] 4. App: PENDING/CONFIRMED/CANCELLED (V19, PLACED rows → CONFIRMED), checkout = pre-check +
      PENDING + OrderCreated (no HTTP reserve/release), `OrderSagaHandler` (conditional UPDATE =
      semantic lock), CONFIRMED publishes OrderPlaced, `GET /api/orders/{id}/status`, sales report
      counts CONFIRMED only. ITs use `FakeSagaParticipants` over real Kafka. verify: 524 tests
- [x] 5. Tests per service + smoke "Saga": normal → CONFIRMED; forced decline → CANCELLED, stock
      restored, RELEASED, no notification; two buyers one unit. Warm smoke 361/0/0
- [x] 6. Orchestration alternative documented (docs/architecture/saga.md); README, decisions (15),
      RECENT (Phase 22 archived), test report, tracker 🔵
- [x] DONE 2026-09-28: `verify` 524 tests green; cold smoke ×3 on the final code = 361/0/0 (runs 9-11;
      two earlier cold runs failed on test-side defects, fixed, in the report §4)

## Planning decisions (user chose all three recommended options, 2026-09-28)
- **Checkout = hybrid.** Read-only `requireAvailable` pre-check stays (instant 409), then the order is
  saved PENDING + `OrderCreatedEvent` in the outbox → 201 with status PENDING. No HTTP reserve/release.
- **Shared `outbox` module** (new reactor module, artifact `ecomdemo-outbox`), used by app, inventory,
  payment. Each service has its own `outbox_event` + `processed_event` tables (Flyway per service).
  Each service's app class adds the package to component, entity and repository scanning.
- **Payment declines above a limit**: `PAYMENT_DECLINE_ABOVE` (default 10000.00). Smoke buys enough
  units to exceed it.
- Topics (publisher declares topic + `-dlt`; 3 partitions; key = order id): `orders.created` (app),
  `inventory.stock-reserved`, `inventory.stock-rejected` (inventory), `payments.completed`,
  `payments.failed` (payment). `orders.placed` now published on CONFIRMED.
- Saga listeners: blocking retries (DefaultErrorHandler, FixedBackOff) → `<topic>-dlt` with partition
  chosen by Kafka (DLT has 1 partition). Keeps per-order ordering; no retry topics.
- Semantic lock = order PENDING (only PENDING → CONFIRMED/CANCELLED; late/duplicate events ignored)
  and inventory's `stock_reservation` rows (RESERVED/RELEASED) which say what to give back.
- Events carry what the next step needs (choreography): StockReserved carries amount + username.

## Next action
Three cold runs (down → up --build --wait → a COPY of smoke-test.sh; never run the repo file while
editing it) are in progress. Then: fill the placeholders in docs/test-reports/phase-24.md
(__RESULT__, __SAGA_BLOCK__, __RUNS__, __MEMORY__) and RECENT.md (__SMOKE__, __PR__), final
`./mvnw clean verify`, commit, push, tracker 🔵, `gh pr create`, Phase Review Report, STOP.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- VM pauses (check `dmesg | grep TimeSync`), Windows port reservations, ~12 s DNS for a stopped container.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).

## ⚠️ Carried, not fixed (oldest first)
- A failed compensating release leaks a reservation; nothing reconciles it. → Phase 24
- The CSV import is a distributed write with no shared transaction (restartable, idempotent). → Phase 24
- **HS256 with a shared secret** — every service can mint as well as verify.
- `ecomdemo-app` is not yet named `order-service`.
- catalog-service has no springdoc; the gateway does no request logging.
- The gateway's `/api/products` route has no breaker; inventory calls have no resilience policy.
- Phase 21 recorded no entries in `docs/decisions.md`; `application.properties` still describes the
  removed customer proxy.
- catalog-service is slow on its first requests after a restart (a half-open trial can exceed 500 ms).
