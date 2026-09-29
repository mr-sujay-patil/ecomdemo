# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-29
- **Phase:** 30 — Performance Testing (Gatling)
- **Branch:** feature/phase-30-gatling (cut from `main` at `9b0912f`)
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification before this phase — PASSED
- Phase 29: PR #46 merged as `9b0912f` (merge commit, 2 parents). 0 missing commits, 0 diffs, branch alive
  locally and on GitHub. CI on main green (run 36477434643). `verify` on main 610/0/0/0. Cold compose
  (down -v, --build, `.smoke-state` removed) smoke on a COPY 404/0/0 with the user's `.env` (Ollama;
  assistant model qwen2.5:7b, confirmed inside the container). `phase-29-complete` already existed on
  `9b0912f` (pushed by an earlier session, whose results were not recorded; re-run in full here).

## Design
(to be decided in IMPLEMENTING; record decisions here, then in `docs/decisions.md`)

## Checklist (from the phase file's "What you'll implement")
- [ ] Browse, checkout, and mixed simulations (Gatling Java DSL)
- [ ] Ramp, steady, and spike load profiles
- [ ] Comparisons with and without the cache and with different pool sizes
- [ ] Findings in `docs/performance.md`
- [ ] Done when: at least one bottleneck found, fixed, and documented (before/after numbers)
- [ ] Testing protocol, test report (Gatling results), README, decisions, RECENT, tracker 🔵, PR

## Next action
Explore how the stack is load-tested today (gateway rate limits, auth, seed data, Hikari/Tomcat/virtual
thread settings, cache switch), then decide the module layout for Gatling and write the Design section.

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
