# Phase 28 Test Report: Semantic Search (pgvector)

- **Date:** 2026-09-28
- **Branch:** `feature/phase-28-semantic-search`
- **Machine:** the WSL2 workstation. Every result below was produced by Claude Code on that machine.
- **Toolchain:** Java 21.0.12, Maven 3.9.16, Docker 29.8.1, Spring Boot 4.1.1, Spring AI 2.0.1
  (new: `spring-ai-starter-vector-store-pgvector`), Spring Batch 6.0.5 (new here:
  `spring-boot-starter-batch-jdbc`), PostgreSQL 18 with **pgvector 0.8.6**
  (`pgvector/pgvector:0.8.6-pg18-trixie`, catalog-db only). The real-model runs used Ollama with
  `nomic-embed-text` (and `llama3.2` for Phase 27's chat) in a throwaway container.
- **Result:** ✅ green.
  - `./mvnw clean verify`: BUILD SUCCESS, **570 tests**, 0 failed, 0 skipped.
  - Compose, cold, default configuration (no embedding model): **377 passed / 0 failed / 1 skipped**.
  - Compose, cold, real models (nomic-embed-text + llama3.2): **385 / 0 / 0**.
  - Kubernetes (existing cluster, upgraded in place): **354 / 0 / 6**.
- **No OpenAI call was made.** There is no key on this machine. ⚠️ See §7.

## 1. Full regression

`./mvnw clean verify`: BUILD SUCCESS, **570 tests** (455 unit, 115 integration), up from 539. New:

| Test | Kind | Count | What it proves |
|---|---|---|---|
| `ProductSearchIndexTest` | unit | 5 | what is embedded (name, description and category, in words; no price) and the document prefix; metadata for the filters (category upper-cased, price a JSON number); filters become one AND-ed expression, or none; with no model every operation is a 503 naming `AI_EMBEDDING_PROVIDER` |
| `SearchPropertiesTest` | unit | 3 | the active provider picks its own threshold and prefixes; an override wins; a BLANK override does not (compose sends unset variables as empty); an unknown provider falls back |
| `ProductIndexerTest` | unit | 5 | re-embeds the product's CURRENT row; removes a missing one; a delete that races the write (FK violation) counts as removed, not failed; a model failure is rethrown so Kafka retries; with no model the event is acknowledged and nothing is read |
| `ProductServiceTest` (+4) | unit | 4 | create, update, a new description, delete and the bulk upsert each announce `ProductChanged`; a refused update announces nothing |
| `SemanticSearchApiIT` | integration (pgvector + Redis) | 10 | the backfill (202, then COMPLETED, every product indexed); a query sharing no word with the laptop sleeve finds it first, and the same words as ILIKE do not; below the threshold is no match; category and price filters inside the vector query; results are current rows with stock; the index follows create, edit and delete (and V4's cascade); a model outage is a 503 with `Retry-After: 30`; a backfill during an outage is FAILED with the root cause, and the index keeps its rows; 400s and a 404; 401 without a token |
| `SemanticSearchNotConfiguredIT` | integration | 2 | the DEFAULT configuration starts; search and the backfill answer 503 with the setup steps, and no job row is written; writes still work and are still announced |
| `EmbeddingSyncIT` | integration (pgvector + Redis + **Kafka**) | 1 | nothing called by hand: API → outbox → relay → Kafka → indexer → pgvector → search, for a create and for an edit |
| `EdgeSecurityIT` (+1) | integration (gateway) | 1 | search is public; the backfill's status is 401 anonymous, 403 for a customer, routed for an ADMIN |

The tests use `ConceptEmbeddingModel` (test support), a deterministic stand-in that maps words to six
concepts. Two texts that share a concept but no words land close together, which is the property
the search code depends on. How well a REAL model ranks is §3's question, not a unit test's.

Two defects were found by these tests before anything ran for real:

- Spring Batch 6.0's JDBC DAO **throws** `EmptyResultDataAccessException` for an unknown execution
  id, where its signature promises null. Asking for 999999 gave a 500. It is now caught and gives a
  404. (The application's `BatchService` makes the same null check. That was not changed, because it
  is outside this phase; it is listed as a follow-up.)
- A job's exceptions are **not persisted**. A run read back from the repository has none, and Batch
  6 wraps the writer's error in "Unable to process chunk". The status now reports the ROOT cause
  from the stored exit description.

## 2. Phase acceptance: the Done-when item

**Done when: natural-language queries return relevant products that keyword search misses.**

✅ Against a real model, in the smoke test (§3) and on the cold stack:

    "I want to listen to music without hearing the plane" -> Noise-Cancelling Headphones 0.578
    PASS  and ranks the product it means first
    PASS  which keyword search misses: none of the query's words is in its name or description

The keyword baseline is not assumed: the smoke test runs `ILIKE '%word%'` against the product's name and
description for every query word longer than three letters (want, listen, music, without, hearing,
plane), and counts **0** matches. The integration test proves the same for the laptop sleeve with the
stand-in model.

**Checklist items:**
- pgvector extension, table and HNSW index via Flyway ✅ (V4).
- Embeddings on create and update through the outbox and events ✅ (`ProductChanged` →
  `catalog.product-changed` → `ProductIndexer`; `EmbeddingSyncIT` and the smoke test's "a new product
  is embedded from its event, with no backfill").
- Spring Batch backfill ✅ (`productEmbeddingBackfill`, JDBC job repository, keyset paging, chunks of 20).
- `GET /api/products/search?q=...` with metadata filters ✅ (`category`, `minPrice`, `maxPrice`, `limit`).

## 3. Measuring a real model, and what the threshold can and cannot do

Ollama with `nomic-embed-text` (768 dimensions) ran in a throwaway container on the compose network.
Port 11434 is reserved by Windows on this host (see Phase 27), and your host Ollama only has
`llama3.2`, which I did not change. The measurement used the fourteen products in the database at the
time: the seed plus probes left by earlier smoke runs. The top three for each query, with the
threshold at 0:

| Query | No prefixes | With nomic's `search_query:` / `search_document:` prefixes |
|---|---|---|
| protect my laptop on the train | **Sleeve** .542, Stand .490 | **Sleeve** .566, SSD .494 |
| something to carry my computer in | Stand .609, **Sleeve** .605 | **Sleeve** .641, Hub .621 |
| I want to listen to music without hearing the plane | **Headphones** .534, Webcam .453 | **Headphones** .578, Webcam .487 |
| somewhere to keep backups of my photos | **SSD** .525, Webcam .502 | Webcam .557, **SSD** .552 |
| typing all day and my wrists hurt | **Keyboard** .506, Mouse .501 | **Keyboard** .507, Mouse .499 |
| video calls with my team | **Webcam** .530 | **Webcam** .572 |
| connect more devices to my laptop | Stand .596, Sleeve .558, **Hub** .550 | Stand .594, **Hub** .585 |
| a garden hose for the roses | Desk Mat .471 | (probe) .488, Desk Mat .483 |
| a sofa for the living room | Desk Mat .603 | Desk Mat .619 |

What this shows:
- **With the prefixes**, the intended product (bold) is first for 5 of the 7 real queries, and first or
  second for all 7. Without them it is first for 5 and third once. The prefixes are the ones nomic was
  trained with, and they are the Ollama default now.
- **Every score falls between about 0.39 and 0.65, relevant or not.** A threshold of 0.5 removes
  queries about nothing in the shop ("garden hose", best 0.488). It keeps every intended product
  measured (the lowest was 0.507), but "a sofa" still returns the desk mat at 0.619. No single number
  separates those for this model. The ranking is useful; the absolute score is weak. That is the
  case for hybrid search (keyword plus vector) or a re-ranker, recorded as a follow-up.
- Where a model fails, the text is usually the cause, not the model. "USB-C Hub" is described as
  "7-in-1 hub: HDMI, Ethernet, SD, 3x USB-A", which never says it connects devices to a laptop.
  Embeddings can only find what the text says.

The threshold and prefixes are therefore **per model** (`ecomdemo.search.models.<provider>.*`), and the
`SEARCH_*` variables override them. The run with those defaults and no overrides gave the same numbers,
and the stored text starts with `search_document: `.

## 4. Compose

| Run | Stack | Configuration | Result |
|---|---|---|---|
| 1 | kept volumes (the upgrade: V4 on a database created by postgres:18-**alpine**) | Ollama embeddings; chat pointed at the same test container, which had no `llama3.2` | 384 / **1** / 0 (Phase 27's generator: 404 "model not found", my setup) |
| 2 | **cold** | default: no embedding model; `.env` has chat on your host Ollama | **377 / 0 / 1** |
| 3 | **cold** | both real models (llama3.2 pulled into the test container) | 381 / **3** / 0 (see below) |
| 4 | **cold** | both real models, final script | **385 / 0 / 0** |
| 5 | **cold** | default, final script | **377 / 0 / 1** |

**Run 3 was a Phase 27 smoke-script flaw, now fixed.** llama3.2 wrote a 71-character SEO title. The
service refused it with 503 "unusable answer" and left the product unchanged, which is the graceful
degradation Phase 27 promised. The smoke script then checked `Retry-After` by sending a **second**
POST. That one succeeded, changed the product and carried no `Retry-After`, so the two follow-up
checks were about a different request from the one they described. Now the headers come from the
refused response itself, and an "unusable answer" (only that kind of refusal) is retried up to three
times, the way a client honouring `Retry-After` would. Run 4's first answer was valid, so the retry path
has run once in anger (run 3) and not since.

Run 2's one skip is the model check, as designed:

    Semantic search
      PASS  search is public, and an empty query is a 400
      PASS  starting the embedding backfill needs a token (401)
      PASS  and an ADMIN one: a customer gets 403
      PASS  a backfill's progress is ADMIN-only too, although it is a GET under /api/products
      PASS  creating a product writes ProductChanged to catalog's outbox, in the same transaction
      PASS  and the relay publishes it to catalog.product-changed
      SKIP  the phase's natural-language query ranks the product it means first
            reason:   no embedding model configured. To run it: install Ollama on the host, run 'ollama
            pull nomic-embed-text' and set AI_EMBEDDING_PROVIDER=ollama in .env (or
            AI_EMBEDDING_PROVIDER=openai with OPENAI_API_KEY); then 'docker compose up -d
            catalog-service' and run this script again
      PASS  search without a model is a 503 that says when to retry
      PASS  the probe product is deleted, and its embedding with it

Run 4's section with a real model: all 16 checks passed. "17 products read, 17 embedded" (the backfill),
"a new product is embedded from its event, with no backfill", the phase's query as in §2, both filters,
and the metrics.

catalog-service's log on run 5: 0 ERROR lines. The WARN lines are Kafka clients reconnecting during the
smoke test's own broker outage, now from two more clients (the indexer and the outbox producer), plus
the springdoc and Hibernate Validator warnings that were already there.

## 5. A real outage, and the repair

With the models configured, the Ollama container was **stopped**:

    GET /api/products/search?q=noise cancelling
    HTTP/1.1 503 Service Unavailable
    Retry-After: 30
    {"status":503,"message":"Search is unavailable: the embedding model did not answer."}   2.55 s

    POST /api/products  "Outage Probe Lamp"   -> 201: a write never waits for the model
    20 s later: no embedding; the event is on catalog.product-changed-dlt:
      {"eventId":"2e6fd5c6-...","productId":39}
    ecomdemo_search_indexing_seconds_count{outcome="failed"} 3      <- three attempts, then dead-lettered

Ollama started again, then `POST /api/products/embeddings/backfill`: **COMPLETED, 15 read, 15 written,
indexedProducts 15 = totalProducts 15**. The lamp had its embedding. This is the designed recovery: the
indexer gives up after three attempts, and the backfill repairs what was missed.

## 6. Kubernetes (kind)

The cluster from Phase 27 was **upgraded in place**, not recreated: `SKIP_BUILD=1 scripts/k8s-up.sh`
(the new images and the catalog-db StatefulSet's new image), then a `rollout restart` of catalog-service
and gateway-service, whose `:latest` tags do not change in the manifests. catalog-db came back on
`pgvector/pgvector:0.8.6-pg18-trixie` with its existing volume, and V4 applied: schema version 4,
`vector` 0.8.6.

`scripts/k8s-smoke.sh`: **354 / 0 / 6**. The 6 skips are the 4 observability sections (they stay in
compose) and the two model checks (the ConfigMap sets both providers to `none`). There are two catalog
pods, so there are two outbox relays. A duplicate send costs one extra embedding call and nothing
else (see `docs/decisions.md`, Phase 28).

## 7. ⚠️ Needs you

- **OpenAI embeddings have not been called.** `text-embedding-3-small` at 768 dimensions, and its 0.3
  threshold, are a starting point, NOT a measurement. To check:

      # in .env:  AI_EMBEDDING_PROVIDER=openai   OPENAI_API_KEY=sk-...
      docker compose up -d catalog-service
      scripts/smoke-test.sh     # the Semantic search model check turns from SKIP into PASS

- **Or for free, on your host Ollama:** `ollama pull nomic-embed-text`, then `AI_EMBEDDING_PROVIDER=ollama`
  in `.env`. Your `.env` already points `OLLAMA_BASE_URL` at the host, and one Ollama serves both models.

## 8. Clean-up

The throwaway Ollama container and its volume were removed. The compose stack was recreated with the
default configuration and is left running; so is the kind cluster (`scripts/k8s-down.sh` removes it).
