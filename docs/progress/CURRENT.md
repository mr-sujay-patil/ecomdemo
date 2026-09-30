# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-30
- **Fix:** KI-001 — Swagger UI and OpenAPI docs unreachable since the split
- **Branch:** fix/ki-001-openapi-docs (cut from `main` at `48e8949`)
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

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
- [ ] Testing protocol steps 1, 3, 4, 7, 8 + regression test; KI-001 → Fixed; PR; PR number in status

## Next action
All fix commits are in. Found on the way and fixed as part of KI-001 (a spec could not load):
Spring AI pinned old swagger-annotations (parent `dependencyManagement`, `swagger-core.version`).
Server is RELATIVE `/` (kind's gateway is on 18080). Catalog's write schema is `ProductRequest`.
Now: `./mvnw clean verify`, cold compose smoke (`down -v`), `SKIP_BUILD=1 scripts/k8s-up.sh` +
`scripts/k8s-smoke.sh`, clean up, then PR `Fix KI-001: ...`, PR number into KNOWN_ISSUES, STOP.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack
  (`down -v`, `up --build --wait`, `.smoke-state` removed): kept volumes hit KI-039/KI-040.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy.
- The user's `.env` sets AI_CHAT_PROVIDER=ollama and AI_EMBEDDING_PROVIDER=ollama. Never print `.env`.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).
- In this Claude shell `grep` is a broken Claude Code wrapper function: use `command grep`.

## ⚠️ Carried, not fixed
Tracked in `docs/KNOWN_ISSUES.md` (open rows: KI-002..011, KI-039, KI-040, and the triage sections).
