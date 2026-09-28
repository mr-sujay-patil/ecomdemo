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

## Phase 25: Container Orchestration (tag: phase-25-complete, PR #__PR__)
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

## Phase 24: Distributed Transactions (tag: phase-24-complete, PR #40)
**What exists now:** checkout is a CHOREOGRAPHED SAGA over Kafka. `POST /api/orders` = stock pre-check
(read, instant 409) + order PENDING + `OrderCreatedEvent` in the outbox -> 201 PENDING. inventory
reserves all lines or none (`inventory.stock-reserved` / `-rejected`), the MOCK payment-service
(8086, payment-db 5437) charges or declines above `PAYMENT_DECLINE_ABOVE`=10000.00
(`payments.completed` / `-failed`), the app moves PENDING -> CONFIRMED / CANCELLED; on a decline
inventory RELEASES the stock (compensation). `GET /api/orders/{id}/status`. 20 containers.
Smoke **361 / 0 / 0** on three cold runs of the final code, WSL2 workstation.
**Key code:** new reactor module `outbox` (`Outbox`, `OutboxRoutes`, `ProcessedEvents`,
`SagaListenerErrors`, `@EnableOutbox`); app `order/internal/saga/OrderSagaHandler` +
`OrderRepository.transition` (conditional UPDATE = semantic lock); inventory `saga/InventorySagaHandler`
+ `InventoryService.reserveForOrder/releaseForOrder` + `stock_reservation`; `payment-service` module.
**Config & infrastructure:** app V18 (`processed_event`), V19 (PENDING/CONFIRMED/CANCELLED, PLACED rows
-> CONFIRMED); inventory V3; payment V1. Each publisher declares its topics (3 partitions) + `-dlt` (1).
Consumer groups `order-service`, `inventory-service`, `payment-service`. Design + orchestration
alternative: `docs/architecture/saga.md`.
**Tests:** outbox module 39 unit; InventorySagaTest (9, Postgres), PaymentServiceTest (5, Postgres),
OrderSagaHandlerTest (5); app ITs answered by `FakeSagaParticipants` over the real Kafka container
(OrderApiIT decline path). Smoke §Saga (25 checks): CONFIRMED, CANCELLED + RELEASED + stock
restored + no notification, two buyers one unit. `./mvnw clean verify` 524 tests.
**Gotchas:** never edit `scripts/smoke-test.sh` while it runs (bash reads it as it goes; run a copy).
A spy of a `@Transactional(MANDATORY)` bean must be stubbed on `AopTestUtils.getUltimateTargetObject`.
`@AutoConfigurationPackage` for a package the app already covers duplicates every repository.
Spring Batch runs the sales report once per date - one IT per day. OrderPlaced (the thank-you) is now
published on CONFIRMATION, so notification tests wait for the whole saga.
**Follow-ups (not done):** a saga timeout (PENDING after a dead-lettered event is forever) and
reservation reconciliation; restore the cart on cancel; notification-service onto `ProcessedEvents`;
prune `processed_event`; remove the now-unused inventory reserve/release HTTP API.

