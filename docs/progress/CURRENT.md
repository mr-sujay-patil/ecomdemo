# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-29
- **Phase:** 30 — Performance Testing (Gatling)
- **Branch:** feature/phase-30-gatling (cut from `main` at `9b0912f`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #47 https://github.com/mr-sujay-patil/ecomdemo/pull/47
- **Waiting for user:** YES - review of the Phase 30 PR

## Merge verification before this phase — PASSED
- Phase 29: PR #46 merged as `9b0912f` (merge commit, 2 parents). 0 missing commits, 0 diffs, branch alive
  locally and on GitHub. CI on main green (run 36477434643). `verify` on main 610/0/0/0. Cold compose
  (down -v, --build, `.smoke-state` removed) smoke on a COPY 404/0/0 with the user's `.env` (Ollama;
  assistant model qwen2.5:7b, confirmed inside the container). `phase-29-complete` already existed on
  `9b0912f` (pushed by an earlier session, whose results were not recorded; re-run in full here).

## Design (decided; decisions.md entries to write)
- Module `performance-tests/` (Gatling 3.15.1 Java DSL, gatling-maven-plugin 4.21.12), OWN pom with no
  Spring Boot parent (Boot's dependency management would override Gatling's Netty/Jackson), NOT in the
  root reactor (Docker images and `verify` stay Gatling-free). CI compiles it: `-f performance-tests/pom.xml
  test-compile`. Run: `./mvnw -f performance-tests/pom.xml gatling:test -Dgatling.simulationClass=...`.
- Target = the GATEWAY (8080), like a real client. Rate limit is per USER (50/s, burst 100) and per IP
  for anonymous -> browse runs AUTHENTICATED with a pool of pre-registered users (anonymous load from
  one host shares one IP bucket; that is a finding, shown once, not the thing measured).
- Data setup in the simulation's `before()` with java.net.http (idempotent): register `perf-user-N`
  (409 = exists), admin creates `Perf Product N` if missing and PUTs stock very high; prices small
  (payment declines above 10000). Tokens fetched once in `before()` and fed -> login/BCrypt measured
  separately, not hidden inside every browse.
- Simulations: Browse (list, detail, search), Checkout (add to cart, place, poll status to
  CONFIRMED/CANCELLED), Mixed (weighted). Profile by `-Dprofile=ramp|steady|spike` (+ rate/duration
  knobs), assertions on failure rate and p95/p99.
- Comparisons via env knobs with today's defaults (compose): catalog cache on/off
  (`spring.cache.type`), Hikari pool sizes; `scripts/perf-compare.sh` restarts with each setting and
  collects Gatling's stats.json into a table. Findings + bottleneck fix in `docs/performance.md`.
- Load generator shares the host with the stack (24 cores, 30 GB): numbers are relative, not absolute.

## Checklist (from the phase file's "What you'll implement")
- [x] Browse, checkout, and mixed simulations (Gatling Java DSL) - `475b819`, harness checked (browse 5/s, checkout 3/s: 0 KO)
- [x] Ramp, steady, and spike load profiles (`PERF_PROFILE`; runner `scripts/perf-test.sh`, results TSV)
- [x] Comparisons with and without the cache and with different pool sizes (`scripts/perf-compare.sh`, compose knobs)
- [x] Findings in `docs/performance.md` (raw runs: `docs/test-reports/phase-30-perf-results.tsv`)
- [x] Done when: bottleneck = outbox relay (`8204022`): steady 50/s settle p95 25.9 s -> 3.0 s
- [x] Testing protocol: verify 616/0/0/0; compose cold smoke 404/0/0; k8s 360/0/7; Gatling acceptance 0 KO; report, README, decisions (7), RECENT (Phase 28 archived), tracker 🔵, PR #47

## Key results (details in docs/performance.md)
- Outbox fix: checkout steady 50/s settle p50/p95 13.0/25.9 s -> 2.5/3.0 s; ramp to 100/s 594 -> 0
  unconfirmed. Cache off: same latency (gateway-bound) but +2.5 cores backend/DB. Pool 2 collapses,
  5/10/30 equal. Mixed spike (3000 in 10 s) 0 KO; accidental 300 checkouts/s = pool exhaustion.
- Simulation bug found+fixed (`3b8ede8`): mixed gave both scenarios the full spike.

## Next action
STOPPED at PR #47, waiting for the user. Do NOT merge unless the user says `approved, merge it`
(`gh pr merge 47 --merge`). On `merged, continue`: merge verification (git checks, CI on main,
`verify`, cold compose smoke on a COPY), tag `phase-30-complete`, then Phase 31 per ROADMAP.
Stack state: compose (user's .env) and kind are running with the Phase 30 build; perf users and
products exist in the compose DBs.

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
