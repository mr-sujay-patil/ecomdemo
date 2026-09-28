# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-28
- **Phase:** 27 — LLM Integration (Spring AI)
- **Branch:** feature/phase-27-spring-ai (cut from `main` at `615889c`)
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Merge verification before this phase — all PASSED
- Phase 25: PR #42 merge commit `615889c` (2 parents); branch is an ancestor of main; 0 missing, 0 diffs,
  branch alive; CI on main green (run 36440494284); `verify` 524/0/0/0; cold compose smoke 360/0/0
  (fresh volumes: probe "different database" path), then restart keeping volumes 361/0/0 (probe
  survived, name and price kept). Tagged `phase-25-complete`.
- **Phase 26 (AKS) SKIPPED** by the user (2026-09-28): tracker ⏭️, decision recorded, no branch, no tag.
- Pre-flight for 27: tree clean, no open PRs. No LLM key in the environment, no Ollama installed.

## Checklist (from the phase file's "What you'll implement")
- [x] Spring AI 2.0.1 (built on Boot 4.1.1) in catalog-service: OpenAI + Ollama starters
- [x] `POST /api/products/{id}/generate-description` (ADMIN at the gateway) → `ProductCopy` record
- [x] Prompt templates: `prompts/product-description-{system,user}.st`
- [x] `AI_CHAT_PROVIDER` = openai | ollama | none (default); OPENAI_API_KEY from env
- [x] AI_TIMEOUT (OpenAI option; own OllamaApi client), 1 retry, all failures → 503; `ecomdemo.ai.tokens`, `ecomdemo.ai.generations`
- [x] Saved to product + `product_description_generation` (V3); failures leave the product unchanged
- [x] Smoke section "LLM integration": auth, 404, 503+Retry-After+unchanged, metrics; 200 path checks JSON, save, history, tokens; no provider → SKIP with setup steps
- [x] Testing protocol (verify 539; compose cold 367/0/1; real Ollama 370/0/0; k8s 346/0/5), test report,
  README, decisions (12), RECENT rotation (Phase 24 archived), tracker 🔵

## Next action
Re-run `./mvnw clean verify` on the docs commit, push, open the PR (template filled in), set Step to
PR_OPEN, and STOP with the Phase Review Report.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy.
- The persistence probe makes the smoke count path-dependent: fresh volumes 360, kept volumes 361.
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
- Intermittent on a cold stack: "the dashboard's orders-per-minute query returns data" (Phase 22 & 27 reports).
- Phase 27: the OpenAI path is untested against a real key (none here); Ollama path verified for real.
