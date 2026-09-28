# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-28
- **Phase:** 28 — Semantic Search (pgvector)
- **Branch:** feature/phase-28-semantic-search (cut from `main` at `1106b0d`)
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** —
- **Waiting for user:** NO

## Merge verification before this phase — PASSED
- Phase 27: PR #43 (`190f782`) + follow-up PR #44 (`1106b0d`, the smoke dashboard-query poll). First
  verification FAILED (cold smoke 368/1/0 twice, the dashboard query race) → fixed in #44. Second: git
  checks PASS (0 missing, 0 diffs, branch alive), CI on main green (run 36456544229), `verify` 539/0/0/0,
  cold compose smoke 369/0/0 (the user's `.env` has AI_CHAT_PROVIDER=ollama). Tagged `phase-27-complete`.

## Design (decided at the start; see decisions.md when written)
- catalog-db image → `pgvector/pgvector:0.8.6-pg18-trixie` (compose, k8s, catalog's Testcontainer).
  Debian vs Alpine collation → V4 REINDEXes `idx_product_name`, `idx_product_category`.
- `product_embedding` (id = product id, FK ON DELETE CASCADE, content, metadata json, vector(768), HNSW
  cosine). PgVectorStore built BY HAND only when an EmbeddingModel exists (`spring.ai.vectorstore.type=none`),
  like Phase 27's ChatClient. `AI_EMBEDDING_PROVIDER` openai (text-embedding-3-small @768) | ollama
  (nomic-embed-text, 768) | none (default).
- Sync: ProductChanged{productId} via the shared outbox (`@EnableOutbox`) in every write transaction →
  topic `catalog.product-changed` → catalog's own indexer (own group) re-embeds CURRENT state or deletes.
- Backfill: Spring Batch job (`spring-boot-starter-batch-jdbc`), ADMIN `POST /api/products/embeddings/backfill`.
- `GET /api/products/search?q=&category=&minPrice=&maxPrice=&limit=` (public GET at the gateway).

## Checklist (from the phase file's "What you'll implement")
- [x] pgvector extension + product_embedding (Flyway V4), images switched
- [x] Embedding provider config; vector store only when configured; 503 otherwise
- [x] Embeddings on create/update (outbox → Kafka → indexer), delete handled
- [x] Spring Batch backfill + endpoint
- [x] `GET /api/products/search` with metadata filters
- [x] Tests (unit, IT with a deterministic fake embedding model, e2e through Kafka)
- [ ] Smoke section "Semantic search"
- [ ] Testing protocol, test report, README, decisions, RECENT rotation, tracker 🔵

## Next action
Code + tests committed (catalog ITs green incl. EmbeddingSyncIT through a real Kafka). Next: compose /
k8s / .env.example config (AI_EMBEDDING_PROVIDER etc.), measure a real model (host Ollama,
nomic-embed-text) to set SEARCH_MIN_SIMILARITY and decide the nomic prefixes, then the smoke section.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy.
- The persistence probe makes the smoke count path-dependent: +1 check with kept volumes.
- The user's `.env` sets AI_CHAT_PROVIDER=ollama (host Ollama). Never print `.env`.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).

## ⚠️ Carried, not fixed (oldest first)
- A saga whose event is dead-lettered leaves the order PENDING; no timeout or reconciliation yet.
- The CSV import is a distributed write with no shared transaction (restartable, idempotent).
- **HS256 with a shared secret** — every service can mint as well as verify.
- `ecomdemo-app` is not yet named `order-service`.
- catalog-service has no springdoc; the gateway does no request logging.
- The gateway's `/api/products` route has no breaker; inventory calls have no resilience policy.
- Phase 21 recorded no entries in `docs/decisions.md`; `application.properties` still describes the
  removed customer proxy.
- catalog-service is slow on its first requests after a restart (a half-open trial can exceed 500 ms).
- k8s: the app must stay at 1 replica (no leader election); observability not in the cluster.
- Phase 27: the OpenAI path is untested against a real key (none here); Ollama path verified for real.
