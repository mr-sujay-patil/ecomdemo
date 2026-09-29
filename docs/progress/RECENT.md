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

## Phase 31: Security Scanning (tag: phase-31-complete, PR #48)
**What exists now:** CI jobs `dependency-scan` (OWASP Dependency-Check 13.0.0, NVD, CVSS >= 7 fails,
test scope skipped) and `image-scan` (all 8 images built in one job, Trivy 0.74.0 by digest,
HIGH/CRITICAL fail, CycloneDX SBOM artifact per image); `publish` needs both. `docs/security.md` =
scan findings + OWASP API Top 10 (2023) review. catalog/inventory now enforce roles themselves.
**Key code:** root pom: `dependency-check-maven` in pluginManagement (run explicitly:
`NVD_API_KEY=... ./mvnw org.owasp:dependency-check-maven:aggregate`), security version overrides
`tomcat.version` 11.0.25, `jackson-bom.version` 3.1.6, `jackson-2-bom.version` 2.21.6 (REMOVE when Boot
manages >= these). `dependency-check-suppressions.xml` (2 false positives, until 2027-03-31),
`.trivyignore.yaml` (empty). catalog `SecurityConfig`: GET any token, writes + `/embeddings/**`
ADMIN|SERVICE; inventory: everything ADMIN|SERVICE (`ServiceTokens.ROLE`).
**Config & infrastructure:** GitHub secret `NVD_API_KEY` (set from the user's `.env`); NVD data cached
in CI (`~/.cache/dependency-check`, first download ~26 min in CI, ~40 min locally); locally the key
is read from `.env` (never print it). `trivy-reports/` gitignored.
**Tests:** +4: `InventorySecurityTest` (CUSTOMER 403 on read/write/reserve, ADMIN ok), `ProductApiIT`
(CUSTOMER reads but 403 on create/delete/backfill; ADMIN writes). CI blocking proven on PR #48 with
a temporary commons-text 1.9 commit, then reverted.
**Gotchas:** Dependency-Check 13 will not run without an NVD key. CPE matching is product-wide:
Kotlin build-tool CVEs hit kotlin-stdlib; pgvector extension CVEs hit the Java client. Dependency-Check
groups related jars (kotlin-reflect under kotlin-stdlib). Trivy writes root-owned files through the
docker socket mount (delete via a container).
**Follow-ups (not done):** login throttling per username; asymmetric JWT + scoped service identities;
bind compose ports to 127.0.0.1; gateway `/actuator/prometheus` not public; pagination on
`GET /api/products`; Trivy config/IaC scanning of Dockerfile and k8s manifests; Dependabot/Renovate.
