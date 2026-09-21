# Phase 13: Caching

| | |
|---|---|
| **Stage** | Stage 5: Performance & Background Work |
| **Technology** | Redis |
| **Branch** | `feature/phase-13-redis` |
| **PR title** | `Phase 13: Caching` |
| **Requires** | `phase-12-complete` tag exists on `main` |
| **Completion tag** | `phase-13-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Redis

**Goal:** Reduce database load on frequent reads.

**What you'll implement**
- Redis in Compose.
- `@Cacheable` on product reads, and `@CacheEvict` / `@CachePut` on writes.
- JSON serialization with a TTL per cache.
- Hit/miss logging.
- A Testcontainers Redis test.

**Concepts to understand**
- Cache-aside
- Invalidation and staleness
- TTL and eviction
- Redis data types
- What not to cache (stock during checkout)

**Done when**
- Repeated reads skip the database, and updates invalidate the cache.

## Smoke test additions (`scripts/smoke-test.sh`)

After a product read, the cache key exists in Redis (`redis-cli`). Updating the product evicts or refreshes the entry.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.
