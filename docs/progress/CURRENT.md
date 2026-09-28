# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-28
- **Phase:** 25 — Container Orchestration (Kubernetes, kind)
- **Branch:** feature/phase-25-kubernetes (cut from `main` at `8047753`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** YES - review of the Phase 25 PR (tools: kind v0.34.0-alpha, kubectl v1.36.1,
  helm v3.22.0).

## Merge verification before this phase — all PASSED
- Phase 24: PR #40 merge commit `67f6889`; 0 missing, 0 diffs; CI green; `verify` 524; cold smoke
  361/0/0; tagged `phase-24-complete`.
- Fix PR #41 (Spring Modulith 2.1.1, not a phase, no tag): merge commit `8047753`; 0 missing, 0 diffs,
  branch alive; CI green; `verify` 524; cold smoke 361/0/0. Dependabot #38 auto-closed.
- Dependabot #39 (ArchUnit 1.5.1) merged by the user as `0ea48da`; CI green; merged into this branch
  (`ac4d08b`).

## Checklist (from the phase file's "What you'll implement")
- [x] Deployment, Service, ConfigMap and Secret per service (k8s/services/, secrets by k8s-up.sh)
- [x] Probes and resource limits (startup/liveness/readiness, CPU request, memory limit)
- [x] An Ingress in front of the gateway (Traefik, localhost:18080)
- [x] Infrastructure through manifests (6 PostgreSQL + Kafka StatefulSets, Redis Deployment)
- [x] An HPA (catalog-service 2-4 @ 60 % CPU; demo 2 → 4)
- [x] Rolling update and self-healing demos (scripts/k8s-demo.sh; rollout 744/0, selfheal 156/0)
- [x] Smoke through the Ingress + Kubernetes section: 338/0/4 on 3 new clusters; compose 361/0/0
- [x] README, decisions (12), test report, RECENT rotation (Phase 23 archived), tracker 🔵

## Planning decisions (user chose all three recommended options, 2026-09-28)
- **Traefik** as the Ingress controller (ingress-nginx is retired, March 2026), installed with Helm.
- **Kustomize** (`k8s/`, `kubectl apply -k`) for our objects; **Helm only for third-party** pieces
  (Traefik, metrics-server).
- **In the cluster:** 7 services + 6 Postgres + Redis + Kafka (StatefulSets with PVCs). Observability
  stays in compose; the smoke test gets a Kubernetes mode (Ingress URL, `kubectl exec` for DB/Kafka,
  observability checks SKIPPED, never passed).

## Next action
STOPPED at the Phase 25 PR, waiting for the user. Do NOT merge unless the user says `approved, merge
it` (`gh pr merge <n> --merge`). On `merged, continue`: merge verification (git checks, `verify`, cold
compose smoke on a COPY of the script, CI green), tag `phase-25-complete`. The kind cluster and the
compose stack may be left running; `scripts/k8s-down.sh` removes the cluster.

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
