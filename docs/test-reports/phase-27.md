# Phase 27 Test Report: LLM Integration (Spring AI)

- **Date:** 2026-09-28
- **Branch:** `feature/phase-27-spring-ai`
- **Machine:** the WSL2 workstation. Every result below was produced by Claude Code on that machine.
- **Toolchain:** Java 21.0.12, Maven 3.9.16, Docker 29.8.1, Spring Boot 4.1.1, **Spring AI 2.0.1**
  (new; OpenAI + Ollama starters; the OpenAI module brings `com.openai:openai-java-core` 4.49.0). The
  real-model run used Ollama 0.34.4 with `llama3.2` in a throwaway container.
- **Result:** ✅ green. `./mvnw clean verify` BUILD SUCCESS (**539 tests**, 0 failed, 0 skipped).
  Compose, cold, no model: **367 passed / 0 failed / 1 skipped**. Compose with a real local model:
  **370 / 0 / 0**. Kubernetes (new kind cluster): **346 / 0 / 5**.
- **No OpenAI call was made.** No key exists on this machine, and none was needed: the OpenAI path is
  the same code as the Ollama path, with a different `ChatModel` bean. ⚠️ See §6.

## 1. Full regression

`./mvnw clean verify`: BUILD SUCCESS, **539 tests** (unit 438, integration 101), up from 524. New:

| Test | Kind | Count | What it proves |
|---|---|---|---|
| `ProductCopyGeneratorTest` | unit | 8 | the prompt (both templates, product text inside `<product>`, the JSON schema appended); parsing and tag clean-up; token and outcome metrics; a provider exception, prose instead of JSON, JSON that breaks the limits and an empty reply are each a 503; `none` is a 503 with setup steps, and no model is called |
| `ProductDescriptionApiIT` | integration (PostgreSQL + Redis containers) | 6 | 200 with the structured copy; the description saved **and the cached product evicted**; one history row; Prometheus series; a failing model → 503 + `Retry-After: 30`, product unchanged, no row; an unusable answer → 503, no row; unknown product → 404 with no model call; no token → 401. Every test's clean-up deletes the product, which proves V3's `ON DELETE CASCADE` |
| `DescriptionGenerationNotConfiguredIT` | integration | 1 | the DEFAULT configuration starts (the context is that start) and the endpoint answers 503 "not configured" with `Retry-After: 300`, while products still read |

All three use `ScriptedChatModel` (test support), a `ChatModel` that answers what the test scripts
and records every prompt. In the ITs it is a bean, so Spring AI's own auto-configuration builds the
`ChatClient.Builder` around it, as it would around a real provider.

## 2. Phase acceptance: the Done-when item

**Done when: generated descriptions are saved, and failures degrade gracefully.**

- **Saved** ✅ — `ProductDescriptionApiIT.generatesAndSaves` (the product's description, read back
  through the cache, plus a `product_description_generation` row), and the smoke test against a
  real model (§4): "the description is saved to the product", "and kept in the generation history".
- **Degrade gracefully** ✅ — four ways, each tested:
  - no provider: `DescriptionGenerationNotConfiguredIT`, and every compose and k8s smoke run
    ("a refusal says when to retry", "and the product is left exactly as it was");
  - provider failure: `ProductDescriptionApiIT.degradesGracefully`, and **a real outage**: with
    the model configured, the Ollama container was stopped and the endpoint was called:

        HTTP/1.1 503 Service Unavailable
        Retry-After: 30
        {"status":503,"message":"The description generator did not answer. The product was not changed."}
        real 0m18.557s          <- two attempts (max-attempts=2, 1 s backoff), each failing on DNS
        product 2 unchanged
        ecomdemo_ai_generations_seconds_count{application="catalog-service",outcome="failed"} 1

  - unusable answers (prose, broken limits, empty): `ProductCopyGeneratorTest`, `ProductDescriptionApiIT.rejectsAnUnusableAnswer`;
  - and in every case the rest of the catalogue keeps working (the smoke runs in §3–§5 pass around it).

**Checklist items:** Spring AI 2.0.1 in catalog-service ✅ (pom); ADMIN endpoint with structured
output mapped to a record ✅ (`ProductCopy`; ADMIN enforced by the gateway's existing
`/api/products/**` rule, proven by the smoke check "a customer gets 403"); prompt templates as
resource files ✅ (`prompts/*.st`, asserted by `buildsThePromptFromTheTemplates`); configurable
provider with the key from the environment ✅ (`AI_CHAT_PROVIDER`; `OPENAI_API_KEY` only in
`.env`/shell, never in the repo); timeouts ✅ (§4, the 18 s outage), error handling ✅, token metrics ✅
(`ecomdemo_ai_tokens_total`, checked by the IT and by the real-model smoke run).

## 3. Compose, cold, no model configured (the default)

`docker compose down -v && docker compose up --build --wait`, then a copy of the smoke test.

| Run | Script | Result |
|---|---|---|
| 1 | with the first LLM section | 366 / **1** / 1 — "the dashboard's orders-per-minute query returns data" (§7) |
| 2 | same, new cold stack | **367 / 0 / 1** |
| 3 | **final script** (after the k8s fix, §5), new cold stack | **367 / 0 / 1** |

The one skip is the model check, as designed:

    LLM integration
      PASS  generating a description needs a token (401)
      PASS  and an ADMIN one: a customer gets 403
      PASS  an unknown product is a 404, before any model is asked
      SKIP  the generator answers with the structured copy: description, tags, SEO title
            reason:   no language model configured. To run it: set AI_CHAT_PROVIDER=openai and
            OPENAI_API_KEY in .env, or install Ollama on the host, run 'ollama pull llama3.2' and set
            AI_CHAT_PROVIDER=ollama; then 'docker compose up -d catalog-service' and run this script again
      PASS  a refusal says when to retry (Retry-After)
      PASS  and the product is left exactly as it was
      PASS  every generation attempt is counted, whatever its outcome
      PASS  the probe product is deleted, generation history and all

catalog-service's log on the cold stack: 0 ERROR lines. The 10 WARN lines were already there before
this phase: springdoc defaults, Kafka during the smoke test's own broker outage, and a Hibernate
Validator deprecation on `ProductController`'s `@Valid List`.

## 4. Compose with a real model (Ollama, llama3.2)

Port 11434 is reserved by Windows on this host (`ports are not available … 500`, the same kind of
reservation as Prometheus's 9090), so Ollama ran **on the compose network** instead:
`docker run -d --name ecomdemo-ollama-test --network ecomdemo_default ollama/ollama`, then
`ollama pull llama3.2` (1 min). catalog-service was recreated with `AI_CHAT_PROVIDER=ollama
OLLAMA_BASE_URL=http://ecomdemo-ollama-test:11434` (shell env, `.env` untouched). The container and
its volume were removed afterwards, and catalog-service was recreated with the default.

A direct call, product 1 (seed: *"Hot-swappable 75% keyboard with tactile switches"*), 8.2 s:

    {"productId":1,"description":"Experience the ultimate typing experience with our hot-swappable 75%
    keyboard featuring tactile switches, perfect for gamers and typists alike.","tags":["mechanical
    keyboard","tactile switches","hot-swappable keyboard"],"seoTitle":"Hot-Swappable 75% Mechanical
    Keyboard with Tactile Switches","model":"llama3.2","promptTokens":493,"completionTokens":84, ...}

Every fact in it comes from the seed. The full smoke test on that stack: **370 / 0 / 0**.

    LLM integration
      PASS  generating a description needs a token (401)
      PASS  and an ADMIN one: a customer gets 403
      PASS  an unknown product is a 404, before any model is asked
      PASS  the generator answers with the structured copy: description, tags, SEO title
            model: llama3.2, tokens: 495 in / 109 out
            seoTitle: LLM Probe Keyboard Gaming Keyboard
      PASS  the description is saved to the product
      PASS  and kept in the generation history
      PASS  the tokens it cost are counted in catalog-service's metrics
      PASS  every generation attempt is counted, whatever its outcome
      PASS  the probe product is deleted, generation history and all

Then the outage in §2. One more check, because the README first claimed otherwise:
`AI_CHAT_PROVIDER=openai` with an **empty** `OPENAI_API_KEY` **starts** healthy. The key is only
used on a call. The README and the properties comment were corrected to say exactly that. No call
was made with the empty key.

## 5. Kubernetes (kind)

The existing cluster's control-plane container had exited (status 128, about 27 minutes earlier,
not caused by this phase), so `scripts/k8s-down.sh && scripts/k8s-up.sh` built a new one (2m43s)
with this branch's images. The catalog ConfigMap sets `AI_CHAT_PROVIDER: none`.

| Run | Result |
|---|---|
| 1 | 344 / **1** / 5 — "every generation attempt is counted": the script read ONE catalog pod through a port-forward, while the Ingress had sent the generation to the other of the two pods |
| 2 | **346 / 0 / 5** — after `catalog_scrape` scraped every catalog pod (`kubectl exec … wget`) and summed |

The 5 skips: the 4 observability sections (they stay in compose, a Phase 25 decision) and the model
check.

## 6. ⚠️ Needs you: OpenAI (or any other real model)

The OpenAI path has not been exercised with a real key, because none exists here. Everything around
it is the same code the Ollama run proved. To check it yourself (it costs a fraction of a cent):

    # in .env (gitignored):  AI_CHAT_PROVIDER=openai   OPENAI_API_KEY=sk-...
    docker compose up -d catalog-service
    scripts/smoke-test.sh          # the LLM section's model check turns from SKIP into PASS

Or for free: install Ollama on Windows or WSL, `ollama pull llama3.2`, set `AI_CHAT_PROVIDER=ollama`.
If 11434 is reserved on your host too, see §4.

## 7. Intermittent, pre-existing: the dashboard query on a cold stack

Run 1's one failure was *"the dashboard's orders-per-minute query returns data"* (Phase 15). It
evaluates `rate(orders_placed_total[5m])`, which needs two scrapes of the counter, and on a cold
stack it can run before the second one. The same query returned data a minute later, and runs 2 and
3 passed. This phase changes neither Prometheus nor the order flow, and the check runs well before
the LLM section. This exact failure is already recorded in `docs/test-reports/phase-22.md` (run 5).
**Carried, not fixed** (out of scope). *Superseded by §9: it failed merge verification twice, and is now fixed.*

## 8. Clean-up

The throwaway Ollama container and its 2 GB volume were removed. catalog-service is back to
`AI_CHAT_PROVIDER=none`. The compose stack and the new kind cluster are left running;
`docker compose down` and `scripts/k8s-down.sh` remove them.

## 9. Merge verification (after PR #43) and follow-up fix

PR #43 merged as `190f782` (2 parents). The branch is an ancestor of `main`, with 0 missing commits and
0 diffs, and the branch is still alive. CI on `main`: success (run 36451495358). `./mvnw clean verify` on
`main`: **539** tests, 0 failed, 0 errors, 0 skipped.

The cold compose smoke **failed twice in a row**, on two new cold stacks: **368 / 1 / 0**, both times
*"the dashboard's orders-per-minute query returns data"* (§7). (0 skipped: the user's `.env` now sets
`AI_CHAT_PROVIDER=ollama` with Ollama on the host, so the model check ran against a real llama3.2 and
passed.) Two failures in two runs is not "occasionally", so no tag was made (execution protocol
§5.4), and the fix went into a follow-up PR from this branch.

**Cause, measured:** `orders_placed_total` is created by the first order, which the smoke test places
itself: Prometheus's first sample of it is `1`. `rate()` needs two samples in the window, and the scrape
interval is 15 s, so the query is correctly empty until the next scrape after that order. The check
asked it once, sometimes before that scrape. The query returned data when asked again a minute later.

**Fix:** the check re-asks for up to 45 s (three scrape intervals) and stops as soon as it gets data.
A wrong label still fails: `application="nope"` returns an empty result, so the check fails after 45 s.

| Run | Stack | Result |
|---|---|---|
| verification 1 | `main`, cold | 368 / **1** / 0 |
| verification 2 | `main`, cold | 368 / **1** / 0 |
| fix 1 | this branch, cold | **369 / 0 / 0** |
| fix 2 | this branch, cold | **369 / 0 / 0** |

Why 369 and not 367: with no model, the LLM section is 7 passes + 1 skip; with a model that answers, it
is 9 passes (the refusal's two checks give way to "answered", "saved", "history" and "tokens"). 367 + 2 =
369 on fresh volumes. Section 4's 370 was on kept volumes (+1 persistence probe).
