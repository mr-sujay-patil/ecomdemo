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

## Phase 28: Semantic Search (tag: phase-28-complete, PR #45)
**What exists now:** catalog-service `GET /api/products/search?q=&category=&minPrice=&maxPrice=&limit=`
(public): embeds the query, nearest products by cosine in pgvector (HNSW), filters inside the query,
threshold per model, results re-read from the catalogue (+stock) with a similarity. Every product write
appends `ProductChanged{eventId, productId}` to catalog's OUTBOX → `catalog.product-changed` (3
partitions, key = id) → catalog's `ProductIndexer` (group `catalog-search-indexer`) re-embeds the
CURRENT row. Spring Batch backfill: `POST /api/products/embeddings/backfill` (ADMIN, 202, async),
`GET .../backfill/{executionId}` (ADMIN at the gateway, rule placed BEFORE the public GET rule).
**Key code:** `com.ecomdemo.catalog.search`: `ProductSearchIndex` (builds PgVectorStore by hand only when
an EmbeddingModel exists; toDocument; Filters), `ProductIndexer`, `ProductSearchService`/`Controller`,
`EmbeddingBackfillJobConfig` (JdbcPagingItemReader keyset, chunk 20, `@BatchTaskExecutor`),
`EmbeddingBackfillService` (root cause from exit description), `SearchProperties` (per-model defaults),
`SearchMessagingConfig` (topics, OutboxRoutes, saga error handler). `ProductChanged` + `ProductService`
announce()/findProduct/findAllInOrder. V4: vector ext, `product_embedding` (id FK product ON DELETE
CASCADE, vector(768), HNSW cosine), outbox + processed_event, BATCH_* tables, REINDEX of the text indexes.
**Config & infrastructure:** catalog-db image `pgvector/pgvector:0.8.6-pg18-trixie` (compose, k8s, catalog
Testcontainer `PgVectorContainerConfig`; k8s-up preloads it). `@EnableOutbox` on catalog.
`AI_EMBEDDING_PROVIDER` none|ollama (`nomic-embed-text`)|openai (`text-embedding-3-small` @768);
`spring.ai.vectorstore.type=none`; `ecomdemo.search.models.{ollama: 0.5 + nomic prefixes, openai: 0.3
UNMEASURED}`, overrides `SEARCH_MIN_SIMILARITY`/`SEARCH_QUERY_PREFIX`/`SEARCH_DOCUMENT_PREFIX` (blank =
unset). `spring-boot-starter-batch-jdbc`, `spring.batch.job.enabled=false`. OllamaClientConfig now applies
when chat OR embedding is ollama. k8s ConfigMap `AI_EMBEDDING_PROVIDER: none`. Metrics
`ecomdemo_search_indexing_seconds{outcome}`, `ecomdemo_search_queries_seconds{outcome}`.
**Tests:** 570 (+31): `SemanticSearchApiIT` 10, `EmbeddingSyncIT` 1 (real Kafka), `SemanticSearchNotConfiguredIT`
2, `ProductSearchIndexTest` 5, `SearchPropertiesTest` 3, `ProductIndexerTest` 5, ProductServiceTest +4,
EdgeSecurityIT +1; stand-in `ConceptEmbeddingModel`. Smoke "Semantic search": compose default 377/0/1,
real models 385/0/0, k8s 354/0/6. The Phase 27 smoke section now reads Retry-After from the refusal
itself and retries an "unusable answer" up to 3 times.
**Gotchas:** Spring AI's PgVectorStore auto-config needs an EmbeddingModel (service won't start with none).
Spring Batch 6.0 JDBC `getJobExecution(unknown)` THROWS EmptyResultDataAccessException; job exceptions are
not persisted and a writer error is wrapped ("Unable to process chunk"). Alpine → Debian image changes
text collation (REINDEX). nomic scores are compressed (0.39–0.65): threshold can't reject everything.
`application-it` sets outbox poll 1h + `spring.kafka.admin.auto-create=false` (no broker in most ITs).
**Follow-ups (not done):** hybrid search (keyword + vector, e.g. RRF) or a re-ranker; measure OpenAI's
threshold; skip unchanged products in the backfill (content hash); expose backfill restart; relay
`FOR UPDATE SKIP LOCKED` (two catalog relays duplicate sends); richer product text (Phase 27's generated
copy) to embed; the app's `BatchService` has the same EmptyResultDataAccessException bug for unknown ids.
