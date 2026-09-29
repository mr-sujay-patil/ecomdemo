# Phase 32 Test Report: Saga Timeouts and Reconciliation

- **Date:** 2026-09-29
- **Branch:** `feature/phase-32-saga-timeouts`
- **Machine:** the WSL2 workstation. Claude Code produced every local result below on that machine.
  CI results are GitHub Actions runs on the PR.
- **Toolchain:** Java 21.0.12, Maven 3.9.16, Docker 29.8.1, Spring Boot 4.1.1, Kafka 4.2.1
  (Testcontainers `apache/kafka`), PostgreSQL 18.
- **Result:** ✅ green.
  - `./mvnw clean verify`: BUILD SUCCESS, **651 tests** (505 unit, 146 integration), 0 failed,
    0 skipped. Up from 620.
  - Done when, **"a dead-lettered saga ends CONFIRMED or CANCELLED (stock released) within the
    deadline"**:
    - automated: `SagaDeadlineIT` (5 scenarios, real Kafka + PostgreSQL)
    - scripted failure scenario: the smoke test's new section, on the running stack
  - Compose, cold (`down -v`, `--build`, `.smoke-state` removed), the user's `.env`: smoke
    **429 / 0 / 0**. The first cold run was 428 / 1 / 0; see §4.
  - Kubernetes (kind, manifests re-applied, all 8 Deployments restarted): smoke **385 / 0 / 7**.

## 1. Full regression

`./mvnw clean verify`: BUILD SUCCESS, 651 tests, 31 more than Phase 31's 620.

| Test | Kind | What it proves |
|---|---|---|
| `PaymentServiceTest` (+4) | PostgreSQL | settling an unpaid order VOIDS it and announces PaymentFailed; a paid order is reported unchanged; settling is idempotent; **a StockReserved after the void charges nothing** (the fence) |
| `PaymentSecurityTest` (+4) | MockMvc, real chains | `/internal/saga/**` is 401 without a token, 403 for a shopper, 200 (VOIDED) for a SERVICE token, 400 without an amount; `/api/**` stays 403 |
| `InventorySagaTest` (+4) | PostgreSQL | closing gives back what is held; closing twice releases nothing more; **a late OrderCreated for a closed order is rejected**; 20 rounds of close racing a reservation never leave stock RESERVED for a closed order (the advisory lock) |
| `InventorySecurityTest` | MockMvc | a shopper is refused `POST /api/inventory/orders/{id}/close` (one new assertion) |
| `SagaReconcilerTest` (7) | unit, mocks | COMPLETED confirms; VOIDED closes the stock **before** cancelling; a decline cancels with its own reason; no payment answer: nothing closed or decided; no inventory answer: not cancelled; a decided order is not asked; a late reply wins |
| `SagaDeadlineIT` (5) | integration: Kafka + PostgreSQL | StockReserved lost: **CANCELLED, stock 3 → 5**; payment reply lost: **CONFIRMED**, OrderPlaced published once; OrderCreated lost: CANCELLED, stock untouched; payment unreachable: stays PENDING, overdue gauge ≥ 1, then CANCELLED once it answers; a finished saga is never touched |
| `DeadLetterIT` (3) | integration: Kafka + PostgreSQL | a DLT record is listed with its original topic and exception; the replay puts the **same bytes** back on the original topic with `ecomdemo-replayed-from`; a second replay is 409; the audit log has it; other topics and missing offsets are 404; a shopper is 403 |
| `OpenApiDocumentationTest` (+3) | Spring context | the three dead-letter paths are documented, and replay requires the bearer token |
| `FlywayMigrationTest` (+1, 2 updated) | H2 | V20 is applied; a DLT position cannot be recorded twice |
| `DashboardMetricsTest` (updated) | unit | both new alert rules query meters `SagaMetrics` really registers |

`ModularityTest` passes with the new `deadletter` module (`messaging`, `shared` only).
`promtool check rules docker/prometheus/alerts.yml`: SUCCESS, 3 rules. I used the
`prom/prometheus:latest` image for this syntax check, not the tag compose pins.

## 2. The scripted failure scenario (smoke: "Saga deadline and dead letters")

On the cold compose stack, against the real services:

1. payment-service is stopped, and a checkout is placed (201, PENDING).
2. inventory reserves the stock (`RESERVED|2`) and publishes StockReserved.
3. **The StockReserved is dead-lettered by hand**, exactly as `SagaListenerErrors` would: it is copied
   to `inventory.stock-reserved-dlt` with the recoverer's headers, and the `payment-service` group's
   offset is moved past it.
4. payment-service is started. The deadline is 60 s.

| Check | Result |
|---|---|
| order status | **CANCELLED**, "Payment was not attempted before the order's deadline" |
| decided | **42 s after payment-service was back** (40 s on the first run); deadline 60 s |
| payment row | `VOIDED` (nothing charged) |
| reservation | `RELEASED|2`, plus a `closed_order` row (the fence) |
| catalogue stock | back to 5 |
| `saga_reconciliations_total{outcome="cancelled"}` | +1 |
| `GET /api/admin/dead-letters` (ADMIN) | lists the record, with `originalTopic` `inventory.stock-reserved` |
| replay | 200, recorded against `admin`; a second replay is **409** |
| after the replay | still one payment, still `VOIDED`; the order is still CANCELLED; stock still 5 |
| security | settle: 401 anonymous, 403 for a shopper; dead-letter API: 403 for a shopper |

All 25 checks in the section pass, on compose and on kind.

On kind, the order was decided **1 s** after payment-service returned. Scaling payment-service back
up took longer than the 60 s deadline, so the order was already overdue when payment could answer.
The sweeps before that had deferred it. That is the unknown-outcome rule working as designed: while
payment-service was down, nothing was decided.

## 3. Kubernetes (kind)

I ran `SKIP_BUILD=1 scripts/k8s-up.sh`, which loads the images compose had just built and
re-applies the manifests, including the app's new `PAYMENT_BASE_URL` and `ORDER_SAGA_DEADLINE`. Then
I ran `kubectl rollout restart` on all eight service Deployments, and every one rolled out.
`scripts/k8s-smoke.sh` gave **385 / 0 / 7**: Phase 31's 360 plus the new section's 25. The seven
SKIPs are the ones earlier phases also had: observability is not in the cluster, and the AI
providers are `none`.

## 4. What went wrong on the way (and was fixed)

- **Two pinned migration lists.** `FlywayMigrationTest` and the smoke test's "flyway_schema_history
  shows V1-V19" both list every migration, so V20 failed them. That is the point of pinning the list.
  Both now expect V20; the smoke check was updated, not removed. First cold smoke run: 428 / 1 / 0.
  After the fix: 429 / 0 / 0.
- **A `@DataJpaTest` that builds `InventoryService` by hand** (`InventoryServiceTest`,
  `StockChangePublisherTest`) needed the new `OrderLocks` bean in its `@Import`.
- **Tooling, not code:** I wrote four edits through a shell heredoc that was also redirected from
  `/dev/null`, so they silently did nothing. I found this because a test that should have been
  updated failed, and redid all four. Separately, the first full build was cut off at the tool's
  10-minute limit and then collided with its re-run. The build that counts is the clean foreground
  run above.

## 5. ⚠️ Needs you

Nothing to set up; there are no new secrets or accounts. To see it yourself:

```bash
docker compose up -d --build --wait
scripts/smoke-test.sh     # the "Saga deadline and dead letters" section is the failure scenario
curl -s localhost:8080/api/admin/dead-letters -H "Authorization: Bearer $ADMIN_TOKEN" | jq
curl -s localhost:8084/actuator/prometheus | grep '^saga_'
```

Prometheus → Alerts shows the two new rules, **SagaOrdersStuck** and
**SagaDeadlineResolvingOrders**. After a smoke run, the second one fires for about 15 minutes. That
is correct: the scenario really did make the deadline cancel an order.
