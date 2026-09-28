# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-28
- **Phase:** 25 — Container Orchestration (Kubernetes, kind)
- **Branch:** feature/phase-25-kubernetes (cut from `main` at `8047753`)
- **Step:** WAITING_FOR_USER
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** YES — the phase's manual step: install `kind` (kubectl v1.36.1 is present;
  helm optional). Docker has 24 CPUs / ~31 GB, enough.

## Merge verification before this phase — all PASSED
- Phase 24: PR #40 merge commit `67f6889`; 0 missing, 0 diffs; CI green; `verify` 524; cold smoke
  361/0/0; tagged `phase-24-complete`.
- Fix PR #41 (Spring Modulith 2.1.1, not a phase, no tag): merge commit `8047753`; 0 missing, 0 diffs,
  branch alive; CI green; `verify` 524; cold smoke 361/0/0. Dependabot #38 auto-closed.
- Dependabot #39 (ArchUnit 1.5.1, CI green) is still OPEN — the user's to merge.

## Checklist (from the phase file's "What you'll implement")
- [ ] Deployment, Service, ConfigMap and Secret per service
- [ ] Probes and resource limits
- [ ] An Ingress in front of the gateway
- [ ] Infrastructure through Helm charts or manifests
- [ ] An HPA
- [ ] Rolling update and self-healing demos
- [ ] Smoke test runs against the Ingress URL; deleting a pod keeps the flow working
- [ ] README, decisions, test report, RECENT rotation, tracker 🔵

## Next action
When the user says `done` (kind installed): verify `kind version`, then plan the manifests and
implement the checklist.

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
