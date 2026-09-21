# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 3: API Documentation (springdoc-openapi)
- **Branch:** feature/phase-03-openapi
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 02 merge verification (passed 2026-09-21)
PR #2 MERGED with a merge commit (513dc40, 2 parents: 85af7b5 + 920caa2); branch is an ancestor
of `main`; no commits or file diffs between branch and `main`; remote and local branches intact;
`./mvnw clean verify` on `main` → BUILD SUCCESS, 79 tests, 0 failures; `scripts/smoke-test.sh`
→ 34 passed, 0 failed, exit 0, no ERROR/WARN in the app log; tag `phase-02-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [ ] springdoc-openapi dependency (version compatible with Spring Boot 4.1.1); Swagger UI at
      `/swagger-ui.html`, spec at `/v3/api-docs`
- [ ] `@Tag`, `@Operation`, `@ApiResponse` on all three controllers
- [ ] `@Schema` examples on the DTOs
- [ ] Documented error responses (404/400/409, the `{status, message}` shape)
- [ ] Smoke test additions: `/v3/api-docs` returns 200 and contains every `/api` path;
      `/swagger-ui.html` returns 200 or redirects
- [ ] Testing protocol run in full + docs/test-reports/phase-03.md
- [ ] README section, decisions.md, RECENT.md rotation, tracker → 🔵, PR raised

## Last test run
- none yet this phase (baseline on `main`: 79 tests green, smoke 34/34)

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet

## Next action
Find the springdoc-openapi version that supports Spring Boot 4.x / Jakarta, add it to `pom.xml`,
and confirm `/v3/api-docs` and `/swagger-ui.html` respond before annotating anything.
