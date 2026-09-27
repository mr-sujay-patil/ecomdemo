# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-27
- **Phase:** between 22 and 23 — `fix/pre-phase-23-hardening` (agreed with the user; not a phase)
- **Branch:** fix/pre-phase-23-hardening (cut from `main` at `4892cb6`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #36 https://github.com/mr-sujay-patil/ecomdemo/pull/36
- **Waiting for user:** YES — review the PR, plus the manual steps below

## Phase 22 merge verification — PASSED, `phase-22-complete` TAGGED at `4892cb6`
PR #35 merged (merge commit), 0 missing commits, 0 diffs, branch alive, CI green; `verify` 2:06 (stack
down); cold smoke **313/0/0**. Details: `docs/test-reports/pre-phase-23-hardening.md` §4.

## This branch — all done
- [x] Kafka: one owner per topic; `orders.placed` race fixed (1/9 → 9/9 forced; poison checks 6/6 cold)
- [x] Memory: heap share 50 %, limits 768 MiB (app 1 GiB); services now 34–48 % of limit
- [x] `EdgeSecurityIT` arranges a closed upstream port (failed with the stack up on `main`)
- [x] Phase 19 dashboard defect: all overview stats range-scoped instant queries
- [x] Phase 22 smoke check "breaker CLOSED again" polls for the claim (was 1-in-3 flaky)
- [x] Machine notes: `docs/process/development-environment.md` — no rationing on the workstation
- Verified: `verify` with **all 17 containers up** BUILD SUCCESS 2:09; cold smoke 313/0/0 ×3.

## Next action
**Waiting for review of the fix PR.** On `approved, merge it`: `gh pr merge <n> --merge`, verify the
merge (ancestor, no diffs, `verify`, cold smoke), then START PHASE 23 per `execution-protocol.md` §3
(pre-flight, `feature/phase-23-tracing`, housekeeping commit marking 22 ✅ in the ROADMAP). No new tag
for this branch — it is not a phase.

### ⚠️ Manual steps for the user (from the test report §5)
- `.env` pins `APP_MEMORY_LIMIT` / `GATEWAY_MEMORY_LIMIT` to the old values → set `1G` / `768M` or delete.
- `stash@{0}` holds the user's local Prometheus-port edit (both sides `19090:19090` — broken). Drop it,
  or use `PROMETHEUS_PORT` in `.env` if Windows reserves 9090 again.

### ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- VM pauses (check `dmesg | grep TimeSync`), Windows port reservations, ~12 s DNS for a stopped container.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).

## ⚠️ Carried, not fixed (oldest first)
- A failed compensating release leaks a reservation; nothing reconciles it. → Phase 24
- The CSV import is a distributed write with no shared transaction (restartable, idempotent). → Phase 24
- **HS256 with a shared secret** — every service can mint as well as verify.
- `ecomdemo-app` is not yet named `order-service`.
- A correlation ID dies at the hop (only the app logs structured). → Phase 23 fixes this.
- catalog-service has no springdoc; the gateway does no request logging.
- The gateway's `/api/products` route has no breaker; inventory calls have no resilience policy.
- Phase 21 recorded no entries in `docs/decisions.md`; `application.properties` still describes the
  removed customer proxy.
- catalog-service is slow on its first requests after a restart (a half-open trial can exceed 500 ms).
