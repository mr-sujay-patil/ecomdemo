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

## Phase 30: Performance Testing (tag: phase-30-complete, PR #TBD)
**What exists now:** Gatling load tests in `performance-tests/` (own pom, NO Boot parent, NOT in the
reactor; CI only test-compiles it): Browse, Checkout (cart -> order -> poll status to CONFIRMED),
Mixed (80/20) x ramp/steady/spike, all through the gateway. The outbox relay now drains while
batches are full (checkout ceiling was ~41 orders/s; now no knee up to 100/s).
**Key code:** `com.ecomdemo.perf` (test sources): `PerfConfig` (PERF_* env), `LoadProfile`
(`injection(share)` scales rate AND spike), `Scenarios`, `TestData` (setup via java.net.http:
perf-user-1..200, `Perf Product 1..20` stock 1 000 000, 429 retry), `SettleTimes` (201 ->
CONFIRMED percentiles, printed in `after()`). `outbox/internal/OutboxRelay` loop +
`OutboxProperties.maxBatchesPerTick` (5th record component).
**Config & infrastructure:** `ecomdemo.outbox.max-batches-per-tick=20` (app properties; default 20
everywhere). compose knobs, defaults = today: `CATALOG_CACHE_TYPE` (redis|none),
`CATALOG_DB_POOL_SIZE`, `APP_DB_POOL_SIZE` (env `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`, no
underscore inside MAXIMUMPOOLSIZE). Run: `scripts/perf-test.sh <browse|checkout|mixed>
[ramp|steady|spike]`, `scripts/perf-compare.sh <cache|app-pool>`; results
`performance-tests/target/perf-results.tsv`, reports `performance-tests/target/gatling/`.
**Tests:** 616 (+6): `OutboxRelayTest` 5, `OutboxPropertiesTest` +1. No smoke additions (by spec).
Compose cold 404/0/0, k8s 360/0/7. Findings: `docs/performance.md`; raw runs
`docs/test-reports/phase-30-perf-results.tsv`.
**Gotchas:** Gatling 3.15 writes no stats.json (the script parses index.html's table); the plugin forks
the JVM, so `-D` doesn't reach simulations (use env). Gatling group stats are CUMULATED request time,
not duration. Anonymous requests from one host share one rate-limit bucket (50/s). JWTs last 15 min,
so runs must stay shorter. `placeOnce()` holds a DB connection across the inventory HTTP call: pool 2
collapses. At high read rates the GATEWAY is the first CPU limit. Gatling shares the host: numbers
are relative.
**Follow-ups (not done):** stock check outside the checkout transaction; checkout load shedding
(503 + Retry-After before the pool queues); pipelined outbox sends; shorter poll-delay (~1.5 s settle
floor = 3 hops); push instead of poll for order status; gateway cost per request (rate-limit Redis
call + JWT) and replicas; separate load machine; soak test.

## Phase 29: AI Shopping Assistant (tag: phase-29-complete, PR #46)
**What exists now:** new **assistant-service** (8087, NO database). `POST /api/assistant/chat
{conversationId?, message}` → `{conversationId, answer, sources[], toolsUsed[], pendingAction?}` and
`POST /api/assistant/actions/{id}/confirm` (both CUSTOMER; gateway route `/api/assistant/**` before the
`app` catch-all). RAG over 5 policy Markdown docs (19 passages, in memory) on every message + tools
`searchProducts` (catalog search), `getOrderStatus` (app), `addToCart` (PROPOSE only; Redis, 10 min,
GETDEL). Every downstream call carries the CALLER's JWT; `com.ecomdemo.clients` not scanned.
**Key code:** `com.ecomdemo.assistant`: `AssistantService` (retrieve → prompt → ChatClient with tools +
memory → tool-limit finish reason → `OrderClaimGuard` → sources), `tools/ShoppingTools` (per request,
holds token + user id; `TextOrJsonResultConverter`; `Turn`), `policy/PolicyLibrary` (chunk by `##`,
one batch embed, cosine), `memory/RedisChatMemoryRepository` (`assistant:memory:{userId}:{convId}`, 24 h,
MULTI/EXEC), `actions/PendingActions`, `store/StoreClient` (RestClient, token relay; 403/404 → empty),
`ai/OllamaClientConfig` (deadline), `ai/ChatMemoryConfig` (window 20). Prompt:
`resources/prompts/assistant-system.st`; policies `resources/policies/*.md`.
**Config & infrastructure:** same switches `AI_CHAT_PROVIDER` + `AI_EMBEDDING_PROVIDER` (BOTH needed,
else 503); `ASSISTANT_OLLAMA_MODEL` default **qwen2.5:7b** (user must pull it), `ASSISTANT_OPENAI_MODEL`
gpt-4.1-mini (untested), temperature 0; policy threshold nomic 0.7 (measured), OpenAI 0.3,
`POLICY_MIN_SIMILARITY`; `spring.ai.tools.limits` 5 total / 3 per tool / THROW. compose service (768M),
Prometheus target, Alloy, k8s Deployment (1 replica, AI none), k8s-up. Metrics
`ecomdemo_assistant_chats_seconds{outcome=answered|not_configured|model_failed|tool_limit|ungrounded|empty}`,
`ecomdemo_assistant_tool_calls_total{tool,outcome}`, `ecomdemo_ai_tokens_total`.
**Tests:** 610 (+40): `AssistantApiIT` 18, `AssistantNotConfiguredIT` 2, `ShoppingToolsTest` 8 (tool
schema has no user param), `PolicyLibraryTest` 6, `OrderClaimGuardTest` 3, `RetrievalPropertiesTest` 2,
`EdgeSecurityIT` +1; support `ScriptedChatModel` (tool-call replies), `FakeStore` (JDK HttpServer),
`HashingEmbeddingModel`. Eval `scripts/assistant-eval.{json,py}` (11 cases): qwen2.5:7b 11/11 ×3,
llama3.2 8/11. Smoke "Shopping assistant": compose default 380/0/3, real models 405/0/0, k8s 360/0/7.
**Gotchas:** `ChatClient.builder(model)` IGNORES `spring.ai.tools.limits` (own ToolCallingManager) → use
the Builder bean. A tool-limit breach is RETURNED as the answer (finish reason `toolCallLimitExceeded`)
and already stored in memory. A ChatModel's options must be `ToolCallingChatOptions` or no tools are
sent. Small models: copy quoted sentences, ignore JSON notes, invent ids → names + plain-text notes.
`compose up --build X` rebuilds/recreates X's deps; the gateway then keeps the OLD IP (500 until restart).
**Follow-ups (not done):** LLM-as-judge on top of the deterministic eval; streaming answers; per-user
rate limit/budget for the assistant; conversation list/delete API; summarising memory; OpenAI
measurement; gateway DNS caching; k8s-up restarting changed Deployments; policies from a CMS instead of
the jar; product-price grounding check like OrderClaimGuard.
