# Phase 25 Test Report: Container Orchestration (Kubernetes)

- **Date:** 2026-09-28
- **Branch:** `feature/phase-25-kubernetes`
- **Machine:** the WSL2 workstation. Every result below was produced by Claude Code on that machine.
- **Toolchain:** kind v0.34.0-alpha (a **pre-release** build; the node image is Kubernetes v1.37.0),
  kubectl v1.36.1, Helm v3.22.0, Traefik chart 41.6.0 (Traefik v3.7.13), metrics-server chart 3.14.0
  (v0.9.0), Docker 29.8.0. No Java dependency changed; ArchUnit 1.5.1 came in from `main` (PR #39).
- **Result:** ✅ green. `./mvnw clean verify` BUILD SUCCESS (524 tests, 0 failed, 0 skipped). Smoke on
  **three freshly created clusters**: **338 passed / 0 failed / 4 skipped** each. The 4 skips are the
  observability sections, which stay in compose by decision. Compose, from a fresh stack, with the same
  script: **361 / 0 / 0**.

## 1. Full regression

`./mvnw clean verify`: BUILD SUCCESS, **524 tests** (unit 430, integration 94). This phase changes no
Java code; the manifests, scripts and smoke test are not part of the Maven build, so the smoke runs
below are the tests of this phase.

## 2. Phase acceptance: the Done-when item

**Done when: the full flow works through the Ingress on the local cluster.** ✅ `scripts/k8s-smoke.sh`
points the whole smoke test at the Ingress (`BASE_URL=http://localhost:18080`, Traefik → gateway). All
338 non-observability checks passed through it, on three new clusters. They cover authentication,
cart, checkout and the saga (CONFIRMED and CANCELLED with compensation), caching, batch import,
Kafka, the outbox under a Kafka outage (Kafka scaled to zero and back), resilience (catalog-service
scaled to zero and back) and the gateway. The new Kubernetes section, from run 3:

    Kubernetes
      PASS  app has a Deployment, Service, ConfigMap and Secret
      PASS  catalog-service has a Deployment, Service, ConfigMap and Secret
      PASS  customer-service has a Deployment, Service, ConfigMap and Secret
      PASS  inventory-service has a Deployment, Service, ConfigMap and Secret
      PASS  notification-service has a Deployment, Service, ConfigMap and Secret
      PASS  payment-service has a Deployment, Service, ConfigMap and Secret
      PASS  gateway-service has a Deployment, Service, ConfigMap and Secret
      PASS  every application container has a startup, liveness and readiness probe
      PASS  and a memory limit and a CPU request
      PASS  the Ingress sends everything to the gateway
      PASS  the HPA on catalog-service reads its CPU (now 9% of request)
      PASS  and keeps between 2 and 4 replicas
      PASS  deleted gateway-service-7465469c67-w4sjf and catalog-service-56b9b8754-b8x94
      PASS  the catalogue still answers through the Ingress
      PASS  a customer can still log in
      PASS  and add to the cart
      PASS  and check out
      PASS  and the saga still confirms the order
      PASS  the deleted pods were REPLACED: gateway 2/2, catalog 3/4 ready
      PASS  by new pods: the deleted ones are gone
      PASS  the gateway rolled out to a new revision
    PASS  and not one of 133 requests during it failed

| Checklist item | Evidence |
|---|---|
| Deployment, Service, ConfigMap and Secret per service | `k8s/services/*.yaml`; smoke checks all 7; Secrets created by `k8s-up.sh`, none in Git |
| Probes and resource limits | startup/liveness/readiness + CPU request + memory limit on every app container (smoke); databases, Kafka and Redis have probes too |
| An Ingress in front of the gateway | `k8s/ingress.yaml` (Traefik); every API check went through it |
| Infrastructure through manifests | 6 PostgreSQL + Kafka as StatefulSets with volumes, Redis as a Deployment (`k8s/data/`) |
| An HPA | `k8s/hpa.yaml`, catalog-service 2–4 at 60 % CPU. Demo: **2 → 4 pods** when load pushed CPU to 172 % of target; the smoke test's own traffic also scaled it to 4 |
| Rolling update and self-healing demos | `scripts/k8s-demo.sh`: rollout **744 requests, 0 failed**; rollback to the previous template; selfheal **156 requests, 0 failed** while a gateway pod was replaced. The smoke test repeats both |

## 3. Found in this round, and fixed

- **Traefik chart values changed shape.** `logs:` is no longer accepted, and a top-level `service.type`
  is silently ignored (it moved under `service.spec`). The ignored key left a `LoadBalancer` Service,
  which on kind waits for ever for an external IP, and `helm --wait` timed out. Fixed, and both chart
  versions are now pinned.
- **Chart downloads timed out** three times in a row on GitHub's release-asset host, then answered in
  0.2 s. `k8s-up.sh` retries each Helm install up to three times.
- **`kind load docker-image` failed** ("content digest … not found") on the multi-platform
  PostgreSQL, Redis and Kafka images from Docker's containerd store. Images are now exported for this
  machine's platform only and loaded as archives.
- **Kafka was never Ready.** The readiness probe (compose's `kafka-cluster.sh`) starts a second JVM that
  inherits the broker's 512 MB heap setting, and it never answered within the probe timeout, although
  the broker was running fine. Because a Service only routes to ready pods, every init container
  waiting for Kafka waited too. It is now a TCP probe. The StatefulSet then needed its pod deleted by
  hand: a StatefulSet does not replace a pod that never became Ready.
- **Smoke check: "by new pods, not the deleted ones"** failed the first cluster run. A deleted pod is
  listed as Terminating while it drains (preStop pause, graceful shutdown). The check now waits for it
  to be gone.
- **Smoke check: "the relay published it and marked it sent"** read 2 in this phase's first compose
  regression. This was a latent Phase 24 flake: on a warm stack the saga's second outbox row
  (OrderPlaced) is published before the check looks. The check now counts the checkout's OrderCreated
  row only.

## 4. ⚠️ Smoke runs, all of them

| Run | Platform | Result |
|---|---|---|
| 1 | cluster (first deploy, fixed by hand as above) | 336 / **1** / 4: "by new pods, not the deleted ones" (see §3) |
| 2 | compose, warm | 360 / **1** / 0: "the relay published it" (see §3) |
| 3 | compose, fresh stack, **final script** | **361 / 0 / 0** |
| 4, 5, 6 | **new cluster each** (`k8s-down` → `k8s-up` → `k8s-smoke`), final code | **338 / 0 / 4** each |

In runs 4–6, `k8s-up.sh` took 137–143 s on a new cluster (images already built) and the smoke test
took 202–209 s. No pod restarted in the final cluster.

## 5. Environment

- **One kind node**, a Docker container named `ecomdemo-control-plane`, with 30 pods in all namespaces.
  The node used **6.3 GiB** of memory after a smoke run. The JVMs used 250–500 MiB each (limits 768 MiB,
  and 1 GiB for the app); Kafka used 435 MiB; each PostgreSQL 53–111 MiB.
- The Ingress is **localhost:18080**. The smoke test's port-forwards use 18084 (app) and 18086
  (payment-service), so compose (8080–8086) and the cluster can run side by side.
- kind v0.34.0-**alpha** is what was installed. It worked throughout; a stable release would be the
  safer default.

## 6. Manual steps for you

1. **Bring it up and look around:** `scripts/k8s-up.sh`, then `kubectl -n ecomdemo get pods,svc,ingress,hpa`
   and `curl -s localhost:18080/api/products | head -c 200`.
2. **Watch the demos** (⚠️ what they *look like* isn't checked automatically):
   `scripts/k8s-demo.sh selfheal`, `rollout`, `rollback`, and `hpa` (about 4 minutes; it starts from 2
   pods only if nothing has loaded catalog-service in the last 5 minutes).
3. **Clean up** when done: `scripts/k8s-down.sh` (the cluster uses about 6 GiB).
