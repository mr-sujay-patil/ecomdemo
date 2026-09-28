## Phase 25: Container Orchestration (tag: phase-25-complete, PR #42)
**What exists now:** the whole system also runs on a local **kind** cluster (one node, k8s v1.37):
`scripts/k8s-up.sh` → Traefik Ingress on **localhost:18080** → gateway (2 replicas) → services. 7
Deployments (ConfigMap + Secret + Service each, startup/liveness/readiness probes, CPU request, memory
limit, maxSurge 1 / maxUnavailable 0, preStop 5 s, init container waiting for dependencies), 6 PostgreSQL
+ Kafka as StatefulSets, Redis Deployment, HPA on catalog-service (2–4 @ 60 % CPU). Observability stays
in compose. Smoke **338 / 0 / 4** (4 = observability skips) on three new clusters; compose 361/0/0.
**Key code:** `k8s/` (Kustomize: `kustomization.yaml`, `data/`, `services/`, `ingress.yaml`, `hpa.yaml`,
`kind-cluster.yaml`, `platform/*-values.yaml`); `scripts/k8s-up.sh|down|smoke|demo.sh`; smoke-test.sh
`ctr_exec`/`ctr_stop`/`ctr_start` (docker or kubectl) + section "Kubernetes" (SMOKE_PLATFORM=k8s).
**Config & infrastructure:** needs kind, kubectl, helm. Charts pinned: Traefik 41.6.0, metrics-server
3.14.0 (`--kubelet-insecure-tls`). Secrets `<svc>-secrets` created by the script (JWT from .env,
DB passwords generated once and kept). Images `docker save --platform` → `kind load image-archive`.
Port-forwards for the smoke test: 18084 (app), 18086 (payment).
**Tests:** no Java changes (524 green). Demos measured: rollout 744 req / 0 failed, selfheal 156 / 0,
HPA 2 → 4 under load.
**Gotchas:** Traefik chart: `service.spec.type`, not `service.type` (else a LoadBalancer that never gets
an IP). A CLI Kafka readiness probe starts a 2nd JVM and never answers → use TCP. A StatefulSet will not
replace a never-Ready pod: delete it. A Service routes only to Ready pods, so init containers waiting on
it wait too. A deleted pod lingers as Terminating (preStop + graceful). `rollout undo` does not change
the YAML (next `apply` reverts it). The app must stay at 1 replica (scheduled jobs).
**Follow-ups (not done):** leader election (ShedLock / Lease) so the app can scale; observability in the
cluster; PodDisruptionBudgets; NetworkPolicies; real secret management (Sealed/External Secrets); a DB
operator; kind stable release instead of the alpha.
