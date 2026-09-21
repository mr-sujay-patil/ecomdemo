# Phase 29: AI Shopping Assistant

| | |
|---|---|
| **Stage** | Stage 8: AI Features |
| **Technology** | RAG + tool calling |
| **Branch** | `feature/phase-29-ai-assistant` |
| **PR title** | `Phase 29: AI Shopping Assistant` |
| **Requires** | `phase-28-complete` tag exists on `main` |
| **Completion tag** | `phase-29-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** RAG + tool calling with Spring AI

**Goal:** A conversational assistant grounded in store data.

**What you'll implement**
- An `assistant-service` with `POST /api/assistant/chat`.
- RAG over products and store policy Markdown documents.
- Tools: `searchProducts`, `getOrderStatus` (current user only), and `addToCart` (with confirmation).
- Conversation memory in Redis.
- Guardrails (store topics only, no cross-user data).
- An evaluation set of about 10 questions.

**Concepts to understand**
- The RAG pipeline
- Tool calling and authorization boundaries
- Memory strategies
- Hallucination and grounding
- LLM evaluation

**Done when**
- The assistant answers accurately, respects user boundaries, and passes the evaluation set.

## Smoke test additions (`scripts/smoke-test.sh`)

The assistant answers a product question, and it refuses to reveal another user's order.

## Your manual steps (user)

Same model/API key setup as Phases 27–28.
