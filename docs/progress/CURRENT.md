# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-30
- **Fix:** KI-001 — Swagger UI and OpenAPI docs unreachable since the split
- **Branch:** fix/ki-001-openapi-docs (cut from `main` at `48e8949`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #52 https://github.com/mr-sujay-patil/ecomdemo/pull/52
- **Waiting for user:** YES (review of PR #52)

## Merge verification before this fix — PASSED (tag `phase-32-complete` on `48e8949`)
- Phase 32: PR #51 `48e8949`, merge commit (2 parents). 0 missing commits, 0 diffs, branch alive
  locally and on GitHub. CI on main green: run 36626179523 (Build, Trivy, Dependency-Check, GHCR).
- On main: `./mvnw clean verify` 651 tests, 0 failed/errored/skipped. Smoke, cold with `down -v`
  (as the Phase 32 report): 429/0/0.
- Smoke with KEPT volumes (`down`, no `-v`): 429 passed / 2 failed (first dead-letter replay 409).
  Root cause: KI-039 (Kafka writes to `/tmp/kafka-logs`, not the volume, so offsets restart) + KI-040
  (replay audit keyed only on topic/partition/offset). The user chose to tag anyway and log both
  (option A, 2026-09-30).

## Checklist (from KI-001's "Fix scope" and "Done when")
- [x] Reproduce first: gateway `ApiDocsIT` (11/11 fail: 401), per-service docs tests (all fail:
      401 / `openApiResource` present), app title test fails
- [x] catalog, customer, inventory, assistant, app: open `/v3/api-docs` in each `SecurityConfig`,
      own title from configuration via `OpenApiConfig`, gateway as the only server
- [x] payment and notification: docs closed or disabled (springdoc off)
- [x] Gateway routes `/v3/api-docs/{service}` and serves one Swagger UI (WebFlux) with a dropdown;
      "Try it out" goes through the gateway
- [x] A spec test per documented service; catalogue-schema test moves to catalog-service
      (`ProductWrite` + constraints); delete the stale comment
- [x] Smoke additions: gateway Swagger UI 200; `/v3/api-docs/{service}` is OpenAPI 3 per service;
      catalog spec has `ProductWrite`; payment/notification expose no docs via the gateway
- [x] README, `security.md` API9, `decisions.md` (`[KI-001] Decision: ...`)
- [x] Testing protocol: verify 668 (506 unit, 162 IT) 0 failed; cold compose smoke (`down -v`) 452/0/0;
      kind (re-applied, 9 Deployments restarted) 408/0/7 (same 7 skips as Phase 32); cleaned up
- [x] PR #52; KI-001 status "Fixed (PR #52)"

## Next action
PR #52 is open. STOP: wait for the user's review. On `approved, merge it` (or `merged, continue`):
merge verification (git checks, CI on main, `./mvnw clean verify` + cold `down -v` smoke on main),
tag `ki-001-fixed`, then the next item: Phase 33 (auth hardening, approved) unless the user queues
another fix (KI-039/KI-040 were found in Phase 32's verification).

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack
  (`down -v`, `up --build --wait`, `.smoke-state` removed): kept volumes hit KI-039/KI-040.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy.
- The user's `.env` sets AI_CHAT_PROVIDER=ollama and AI_EMBEDDING_PROVIDER=ollama. Never print `.env`.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).
- In this Claude shell `grep` is a broken Claude Code wrapper function: use `command grep`.

## ⚠️ Carried, not fixed
Tracked in `docs/KNOWN_ISSUES.md` (open rows: KI-002..011, KI-039, KI-040, and the triage sections).
