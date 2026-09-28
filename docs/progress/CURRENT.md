# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-28
- **Phase:** 29 — AI Shopping Assistant (RAG + tool calling)
- **Branch:** feature/phase-29-ai-assistant (cut from `main` at `5fb6ac1`)
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification before this phase — PASSED
- Phase 28: PR #45 merged as `5fb6ac1` (merge commit, 2 parents). 0 missing commits, 0 diffs, branch alive
  locally and on GitHub. CI on main green (run 36466418725). `verify` on main 570/0/0/0. Cold compose
  (down -v, --build) smoke on a COPY 385/0/0 (the user's `.env` now sets AI_EMBEDDING_PROVIDER=ollama too).
  Tagged `phase-28-complete`.

## Design (decided at the start; decisions.md entries to write)
- New module `assistant-service` (port 8087), NO database: Redis holds memory and pending actions.
- `POST /api/assistant/chat {conversationId?, message}` -> `{conversationId, answer, sources[], pendingAction?}`;
  `POST /api/assistant/actions/{id}/confirm` does the cart write. Gateway: `/api/assistant/**` CUSTOMER,
  route before the `app` catch-all.
- The assistant acts AS THE USER: every downstream call carries the caller's own JWT (catalog search,
  app orders/cart); no service token, no userId argument on any tool. The owner service decides.
- Tools: `searchProducts` (catalog `/api/products/search`), `getOrderStatus` (app `/api/orders/{id}/status`;
  403 and 404 both answer "not an order on your account"), `addToCart` = PROPOSE only (pending action in
  Redis, TTL 10 min); the user confirms outside the model.
- RAG over policies: `resources/policies/*.md`, chunked by `##`, embedded lazily into an in-memory
  SimpleVectorStore (tiny corpus shipped in the jar), top-k above a threshold into the system prompt, cited
  in `sources`. Needs BOTH a chat and an embedding model; otherwise 503 with setup steps.
- Memory: own `ChatMemoryRepository` on StringRedisTemplate (the Spring AI Redis one pulls Jedis + gson +
  a search index), key `assistant:memory:{user}:{conversationId}`, TTL; MessageWindowChatMemory.
- Guardrails: store topics only (fixed refusal sentence), grounded answers, input size limit, identity
  from the token only, memory per user, confirmation for writes.
- Evaluation: ~10 cases in `scripts/assistant-eval.json`, run by `scripts/assistant-eval.py` against
  the live stack (deterministic checks: expected facts, tool/sources used, refusals, no leak).
- Registration points: root pom, Dockerfile, compose, prometheus, alloy, k8s (kustomization, service,
  ConfigMap, k8s-up), ContainerMemoryBudgetTest (7 -> 8), README.

## Checklist (from the phase file's "What you'll implement")
- [ ] assistant-service + `POST /api/assistant/chat` (+ gateway route, compose, k8s)
- [ ] RAG over products and policy Markdown documents
- [ ] Tools searchProducts, getOrderStatus (current user only), addToCart (with confirmation)
- [ ] Conversation memory in Redis
- [ ] Guardrails (store topics only, no cross-user data)
- [ ] Evaluation set (~10 questions), passing with a real model
- [ ] Smoke: answers a product question; refuses another user's order
- [ ] Testing protocol, test report, README, decisions, RECENT rotation, tracker

## Next action
Implement the checklist on `feature/phase-29-ai-assistant`, in order. Nothing written yet.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy.
- The persistence probe makes the smoke count path-dependent: +1 check with kept volumes.
- The user's `.env` sets AI_CHAT_PROVIDER=ollama and AI_EMBEDDING_PROVIDER=ollama (host Ollama:
  llama3.2, nomic-embed-text; RTX 5070 Ti). Never print `.env`.
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
- Phase 27/28: OpenAI chat and embeddings untested against a real key (none here); Ollama verified for real.
- In this Claude shell `grep` is a broken Claude Code wrapper function: use `command grep`.
