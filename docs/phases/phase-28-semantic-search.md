# Phase 28: Semantic Search

| | |
|---|---|
| **Stage** | Stage 8: AI Features |
| **Technology** | pgvector |
| **Branch** | `feature/phase-28-semantic-search` |
| **PR title** | `Phase 28: Semantic Search` |
| **Requires** | `phase-27-complete` tag exists on `main` |
| **Completion tag** | `phase-28-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** pgvector (Spring AI VectorStore)

**Goal:** Search products by meaning.

**What you'll implement**
- The pgvector extension (Flyway).
- Embeddings generated on product create and update (through events or the outbox), plus a Spring Batch backfill.
- `GET /api/products/search?q=...` with metadata filters.

**Concepts to understand**
- Embeddings and cosine similarity
- HNSW indexes
- Semantic vs keyword vs hybrid search
- Keeping embeddings in sync

**Done when**
- Natural-language queries return relevant products that keyword search misses.

## Smoke test additions (`scripts/smoke-test.sh`)

The natural-language query from the phase returns the expected product(s).

## Your manual steps (user)

Same model/API key setup as Phase 27, plus an embedding model.
