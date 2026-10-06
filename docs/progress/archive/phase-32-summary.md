## Phase 32: Saga Timeouts and Reconciliation (tag: phase-32-complete, PR #51)
**What exists now:** the order service owns the saga's clock. `SagaDeadlineSweeper` (@Scheduled, every
`ecomdemo.saga.sweep-interval` 10s) reconciles orders PENDING longer than `ecomdemo.saga.deadline` (1m,
env `ORDER_SAGA_DEADLINE`): payment `settle` → COMPLETED confirms; FAILED/VOIDED → inventory `close`
→ cancel; no answer → DEFERRED (stays PENDING). Admin DLT list/replay with audit. Two alerts.
**Key code:** app `order.internal.saga`: `SagaReconciler`, `SagaDeadlineSweeper`, `OrderDecisions`
(confirm/cancel shared with `OrderSagaHandler`), `SagaParticipants` + `HttpSagaParticipants` (own
RestClients, 1s/5s timeouts), `SagaProperties`, `SagaMetrics` (public, for DashboardMetricsTest). New
module `deadletter` (`DeadLetterService`, `/api/admin/dead-letters`, V20 `dead_letter_replay`).
payment: `POST /internal/saga/orders/{id}/settle` (SERVICE only, 2nd security chain `@Order(1)`),
status VOIDED (V2). inventory: `POST /api/inventory/orders/{id}/close`, `closed_order` (V4),
`OrderLocks` (pg_advisory_xact_lock per order) taken by `reserveForOrder` and `closeOrder`.
**Config & infrastructure:** app env `PAYMENT_BASE_URL` (compose + k8s), `ORDER_SAGA_DEADLINE`. Meters
`saga_orders_overdue`, `saga_reconciliations_total{outcome=confirmed|cancelled|deferred|already_decided}`.
Alerts `SagaOrdersStuck` (10m), `SagaDeadlineResolvingOrders` (fires ~15m after any smoke run).
**Tests:** 651 (505 unit, 146 IT). +`SagaReconcilerTest`, `SagaDeadlineIT` (FakeSagaParticipants can
LOSE_ORDER_CREATED / LOSE_STOCK_RESERVED / LOSE_PAYMENT_REPLY, PAYMENT_UNREACHABLE; its `Settlement`
is @Primary `SagaParticipants`), `DeadLetterIT`, payment/inventory settle/close/fence/race tests.
Smoke section "Saga deadline and dead letters" (stops payment, forges a DLT with kafka CLI, resets
the payment-service group offset): compose 429/0/0, kind 385/0/7.
**Gotchas:** `it`/`test` profiles set `ecomdemo.saga.sweep-enabled=false`; tests call
`sweep(Instant)`. `FlywayMigrationTest` and the smoke test PIN the app's migration list: add each new
V there. A `@DataJpaTest` importing `InventoryService` needs `OrderLocks` too. Advisory locks are
PostgreSQL-only: inventory's close/reserve can't run on H2.
**Follow-ups (not done):** KI-037 (a dead-lettered StockRejected is cancelled with the void's reason);
KI-038 (sweep in every instance, safe); KI-001 Swagger/OpenAPI fix next; restore cart on cancel (KI-019).
