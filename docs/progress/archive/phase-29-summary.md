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
