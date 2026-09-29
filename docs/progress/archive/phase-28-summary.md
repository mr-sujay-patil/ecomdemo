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
