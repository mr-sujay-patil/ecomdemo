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

## Phase 27: LLM Integration (tag: phase-27-complete, PR #43) — Phase 26 (AKS) was SKIPPED
**What exists now:** catalog-service `POST /api/products/{id}/generate-description` (ADMIN at the
gateway): Spring AI 2.0.1 → `ProductCopy` record (description, tags, seoTitle), validated, description
saved to the product (cache evicted), full answer + model + tokens in `product_description_generation`
(V3, ON DELETE CASCADE). Provider by `AI_CHAT_PROVIDER` = openai | ollama | **none (default)**; none or
any failure → 503 + Retry-After, product unchanged.
**Key code:** `com.ecomdemo.catalog.ai`: `ProductCopyGenerator` (the only model caller; converter by
hand; metrics), `ProductDescriptionService` (no tx around the call; TransactionTemplate for the save),
`OllamaClientConfig` (own OllamaApi with a deadline), `ProductDescriptionController`;
`ProductService.replaceDescription`. Prompts: `resources/prompts/product-description-{system,user}.st`.
**Config & infrastructure:** env `AI_CHAT_PROVIDER`, `OPENAI_API_KEY`, `OPENAI_MODEL` (gpt-4.1-mini),
`OLLAMA_MODEL` (llama3.2), `OLLAMA_BASE_URL` (host.docker.internal:11434 via extra_hosts),
`AI_TIMEOUT` (30s/attempt, 2 attempts), `AI_TEMPERATURE` (0.4). All `spring.ai.model.*` keys set. k8s
ConfigMap `AI_CHAT_PROVIDER: none`. Metrics `ecomdemo_ai_tokens_total{type}`,
`ecomdemo_ai_generations_seconds{outcome=success|failed|invalid|not_configured}`.
**Tests:** 539 (+15): `ProductCopyGeneratorTest` 8, `ProductDescriptionApiIT` 6,
`DescriptionGenerationNotConfiguredIT` 1, all on `ScriptedChatModel` (test support). Smoke section "LLM
integration": compose 367/0/1 (the 1 = model check SKIP without a provider), with real Ollama 370/0/0,
k8s 346/0/5.
**Gotchas:** Spring AI REGISTERS `ChatClient.Builder` even with no ChatModel and resolving it fails → ask
`ObjectProvider<ChatModel>` first. A Spring placeholder default does not apply to an EMPTY env var (hence
one model var per provider). `spring.http.clients.*` would change the inventory client too. Windows
reserves host port 11434 here (ran Ollama on the compose network). Per-pod counters in k8s: sum all pods.
**Follow-ups (not done):** Anthropic/Azure providers; a key in k8s (Secret); approving a draft before it
replaces the description; tags/SEO title on the product API; per-admin rate limit or budget for
generations; streaming; the Hibernate Validator `@Valid List` deprecation warning in ProductController.

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
