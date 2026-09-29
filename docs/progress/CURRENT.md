# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-29
- **Phase:** 32 — Saga Timeouts and Reconciliation
- **Branch:** feature/phase-32-saga-timeouts (cut from `main` at `1f3fa7c`)
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification before this phase — PASSED
- Phase 31: PR #48 `2a3d74a` + follow-up #49 `1f3fa7c`, both merge commits. 0 missing commits, 0 diffs,
  branch alive locally and on GitHub. `verify` on main 620/0/0/0 and cold compose smoke 404/0/0 (at
  `2a3d74a`; #49 changed only CI workflows and docs).
- CI on main ALL GREEN: run 36574990023 (rerun, 2026-09-29 18:26-18:51 UTC) - Build, Trivy,
  Dependency-Check (NVD 399,318/399,318 downloaded, cache `nvd-36574990023-4` saved, 141 deps,
  only MEDIUM findings max CVSS 6.5 < 7, 2 known suppressions) and Publish to GHCR.
- Phase 32 was started before this passed (user's choice); tagged `phase-31-complete` on `1f3fa7c`
  once it did.

## Checklist (from the phase file's "What you'll implement")
- [ ] A saga deadline: an order still PENDING after a configurable time is resolved
- [ ] Reconciliation: ask inventory and payment what happened before cancelling (confirm a slow
      success; a cancellation releases reserved stock)
- [ ] See and replay dead-lettered saga events (`*-dlt`), with an audit trail
- [ ] Metrics and an alert for stuck and timed-out orders
- [ ] Done when: a dead-lettered saga ends CONFIRMED or CANCELLED (stock released) within the
      deadline — automated test + scripted failure scenario
- [ ] Testing protocol, report, README, decisions, RECENT, tracker 🔵, PR

## Design (agreed in session 2026-09-29; see also docs/architecture/saga.md)
- Order service owns the clock: `SagaDeadlineSweeper` (@Scheduled, `ecomdemo.saga.deadline` PT1M,
  sweep PT10S) → `SagaReconciler` per overdue PENDING order.
- Ask-and-fence, payment first: payment-service `POST /internal/saga/orders/{id}/settle` (SERVICE
  only, own security chain; `/api/**` stays deny-all) returns the existing payment, or records a
  VOIDED one (+ PaymentFailed via outbox) so a late StockReserved can never charge.
  COMPLETED → confirm. FAILED/VOIDED → inventory `POST /api/inventory/orders/{id}/release` (releases
  RESERVED + writes a `closed_order` fence so a late/replayed OrderCreated is rejected) → cancel.
  Payment or inventory unreachable = unknown outcome → leave PENDING, retry next sweep.
- Reconciliation clients: app-local `SagaParticipants` with timeouts (KI-004 stays separate).
- DLT admin (app, ADMIN): `GET /api/admin/dead-letters`, `POST .../{topic}/{partition}/{offset}/replay`
  → raw bytes back to the original topic, audit row in `dead_letter_replay` (unique → 409 twice).
- Metrics: gauge `ecomdemo.saga.orders.overdue`, counter `ecomdemo.saga.reconciliations{outcome}`;
  alerts SagaOrdersStuck, SagaDeadlineCancellations.
- Smoke: stop payment-service, checkout, move its StockReserved to the DLT + skip the offset, start
  payment → CANCELLED + stock back within deadline; replay → still CANCELLED, no charge.

## Next action
Task 1: payment-service settle endpoint (VOIDED status migration V2, `PaymentService.settle`,
`/internal/saga/**` chain, tests). Then inventory (task 2), app (3, 4), alerts/smoke (5), docs (6).

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy.
- The persistence probe makes the smoke count path-dependent: +1 check with kept volumes.
- The user's `.env` sets AI_CHAT_PROVIDER=ollama and AI_EMBEDDING_PROVIDER=ollama (host Ollama:
  llama3.2, nomic-embed-text; RTX 5070 Ti). Never print `.env`.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).

## ⚠️ Carried, not fixed (oldest first)
- A saga whose event is dead-lettered leaves the order PENDING (THIS PHASE fixes it).
- The CSV import is a distributed write with no shared transaction (restartable, idempotent).
- **HS256 with a shared secret** — every service can mint as well as verify.
- `ecomdemo-app` is not yet named `order-service`.
- catalog-service has no springdoc; the gateway does no request logging.
- The gateway's `/api/products` route has no breaker; inventory calls have no resilience policy.
- Phase 21 recorded no entries in `docs/decisions.md`; `application.properties` still describes the
  removed customer proxy.
- catalog-service is slow on its first requests after a restart (a half-open trial can exceed 500 ms).
- k8s: the app must stay at 1 replica (no leader election); observability not in the cluster.
- Phase 27/28: OpenAI chat and embeddings untested against a real key (none here); Ollama verified for real.
- In this Claude shell `grep` is a broken Claude Code wrapper function: use `command grep`.
- OpenAPI/Swagger was never adapted to the split (found 2026-09-29): only the app (8084) serves
  `/v3/api-docs`; catalog/customer/inventory/assistant answer 401, payment/notification 403; the
  gateway does not aggregate; `OpenApiConfig` still describes the monolith. Not scheduled yet.
