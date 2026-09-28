## Phase 27: LLM Integration (tag: phase-27-complete, PR #43) — Phase 26 (AKS) was SKIPPED
**What exists now:** catalog-service `POST /api/products/{id}/generate-description` (ADMIN at the
gateway): Spring AI 2.0.1 → `ProductCopy` record (description, tags, seoTitle), validated, description
saved to the product (cache evicted), full answer + model + tokens in `product_description_generation`
(V3, ON DELETE CASCADE). Provider by `AI_CHAT_PROVIDER` = openai | ollama | **none (default)**; none or
any failure → 503 + Retry-After, product unchanged.
**Key code:** `com.ecomdemo.catalog.ai`: `ProductCopyGenerator` (the only model caller; converter by
hand; metrics), `ProductDescriptionService` (no tx around the call; TransactionTemplate for the save),
`OllamaClientConfig` (own OllamaApi with a deadline), `ProductDescriptionController`;
`ProductService.replaceDescription`. Prompts: `resources/prompts/product-description-{system,user}.st`.
**Config & infrastructure:** env `AI_CHAT_PROVIDER`, `OPENAI_API_KEY`, `OPENAI_MODEL` (gpt-4.1-mini),
`OLLAMA_MODEL` (llama3.2), `OLLAMA_BASE_URL` (host.docker.internal:11434 via extra_hosts),
`AI_TIMEOUT` (30s/attempt, 2 attempts), `AI_TEMPERATURE` (0.4). All `spring.ai.model.*` keys set. k8s
ConfigMap `AI_CHAT_PROVIDER: none`. Metrics `ecomdemo_ai_tokens_total{type}`,
`ecomdemo_ai_generations_seconds{outcome=success|failed|invalid|not_configured}`.
**Tests:** 539 (+15): `ProductCopyGeneratorTest` 8, `ProductDescriptionApiIT` 6,
`DescriptionGenerationNotConfiguredIT` 1, all on `ScriptedChatModel` (test support). Smoke section "LLM
integration": compose 367/0/1 (the 1 = model check SKIP without a provider), with real Ollama 370/0/0,
k8s 346/0/5.
**Gotchas:** Spring AI REGISTERS `ChatClient.Builder` even with no ChatModel and resolving it fails → ask
`ObjectProvider<ChatModel>` first. A Spring placeholder default does not apply to an EMPTY env var (hence
one model var per provider). `spring.http.clients.*` would change the inventory client too. Windows
reserves host port 11434 here (ran Ollama on the compose network). Per-pod counters in k8s: sum all pods.
**Follow-ups (not done):** Anthropic/Azure providers; a key in k8s (Secret); approving a draft before it
replaces the description; tags/SEO title on the product API; per-admin rate limit or budget for
generations; streaming; the Hibernate Validator `@Valid List` deprecation warning in ProductController.
