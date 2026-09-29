# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-29
- **Phase:** 32 — Saga Timeouts and Reconciliation
- **Branch:** feature/phase-32-saga-timeouts (cut from `main` at `1f3fa7c`)
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification before this phase — PARTIAL (user-approved exception)
- Phase 31: PR #48 `2a3d74a` + follow-up #49 `1f3fa7c`, both merge commits. 0 missing commits, 0 diffs,
  branch alive locally and on GitHub. `verify` on main 620/0/0/0 and cold compose smoke 404/0/0 (at
  `2a3d74a`; #49 changed only CI workflows and docs). CI on main: Build + Trivy green (run 36574990023).
- **KNOWN BLOCKER:** CI's Dependency-Check job on main was CANCELLED, not passed: no NVD cache on main
  and the NVD API was very slow on 2026-09-29 (first full download hit the 60-min limit at ~25%).
  Publish to GHCR was therefore skipped. The user chose (2026-09-29) to start Phase 32 anyway and
  run the NVD workflow later. `phase-31-complete` is deliberately NOT tagged until it is green.
- To clear it: `gh run list --workflow nvd-data.yml` (scheduled daily 03:17 UTC; or
  `gh workflow run nvd-data.yml`); when it succeeds, rerun main's CI (`gh run rerun 36574990023`);
  when every job is green, `git tag -a phase-31-complete 1f3fa7c` and push it; tracker row 31 → ✅
  on the current phase branch.

## Checklist (from the phase file's "What you'll implement")
- [ ] A saga deadline: an order still PENDING after a configurable time is resolved
- [ ] Reconciliation: ask inventory and payment what happened before cancelling (confirm a slow
      success; a cancellation releases reserved stock)
- [ ] See and replay dead-lettered saga events (`*-dlt`), with an audit trail
- [ ] Metrics and an alert for stuck and timed-out orders
- [ ] Done when: a dead-lettered saga ends CONFIRMED or CANCELLED (stock released) within the
      deadline — automated test + scripted failure scenario
- [ ] Testing protocol, report, README, decisions, RECENT, tracker 🔵, PR

## Next action
Housekeeping commit done (phase files 32 + 33, tracker rows, this checkpoint). Next: design the
saga deadline and reconciliation (read `ecomdemo-app/.../order/internal/saga/` and the inventory
and payment saga handlers first), then implement checklist item 1. The Phase 31 blocker above is
independent: clear it whenever the NVD workflow has succeeded.

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
