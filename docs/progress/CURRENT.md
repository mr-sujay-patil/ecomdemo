# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-28
- **Phase:** between 24 and 25 — `fix/spring-modulith-2` (agreed with the user; not a phase)
- **Branch:** fix/spring-modulith-2 (cut from `main` at `67f6889`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** see `gh pr list --head fix/spring-modulith-2`
- **Waiting for user:** YES — review of the fix PR; also Dependabot #39 (ArchUnit, CI green) to merge

## Phase 24 merge verification — PASSED, `phase-24-complete` TAGGED at `67f6889`
PR #40 merged as a merge commit (2 parents); 0 missing commits, 0 diffs, branch alive; CI on `main`
green; `verify` on `main` 524 tests (stack down); cold smoke **361/0/0**.

## This branch
- [x] `spring-modulith.version` 2.1.1 (supersedes Dependabot #38, which failed to compile)
- [x] `ModularityTest`: `new Documenter(MODULES, Documenter.Options.defaults().withOutputFolder(...))`
- [x] docs/modules regenerated: style only, all 14 diagrams keep identical `Rel(...)` lines
- Verified: `verify` 524/0/0; cold smoke 361/0/0.

## Next action
Waiting for review of the fix PR. On `approved, merge it`: merge with `--merge`, verify it (ancestor,
no diffs, `verify`, cold smoke on a COPY of the script), then START PHASE 25 per
`execution-protocol.md` §3 (its first commit marks Phase 24 ✅ in the ROADMAP tracker). Dependabot
#38 closes itself once `main` has 2.1.1; #39 is the user's to merge.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- VM pauses (check `dmesg | grep TimeSync`), Windows port reservations, ~12 s DNS for a stopped container.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).

## ⚠️ Carried, not fixed (oldest first)
- A saga whose event is dead-lettered leaves the order PENDING; no timeout or reconciliation yet
  (Phase 24 follow-up; the HTTP compensation that leaked is gone).
- The CSV import is a distributed write with no shared transaction (restartable, idempotent).
- **HS256 with a shared secret** — every service can mint as well as verify.
- `ecomdemo-app` is not yet named `order-service`.
- catalog-service has no springdoc; the gateway does no request logging.
- The gateway's `/api/products` route has no breaker; inventory calls have no resilience policy.
- Phase 21 recorded no entries in `docs/decisions.md`; `application.properties` still describes the
  removed customer proxy.
- catalog-service is slow on its first requests after a restart (a half-open trial can exceed 500 ms).
