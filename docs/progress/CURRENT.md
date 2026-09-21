# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 3: API Documentation (springdoc-openapi)
- **Branch:** feature/phase-03-openapi
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 02 merge verification (passed 2026-09-21)
PR #2 MERGED with a merge commit (513dc40, 2 parents: 85af7b5 + 920caa2); branch is an ancestor
of `main`; no commits or file diffs between branch and `main`; remote and local branches intact;
`./mvnw clean verify` on `main` → BUILD SUCCESS, 79 tests, 0 failures; `scripts/smoke-test.sh`
→ 34 passed, 0 failed, exit 0, no ERROR/WARN in the app log; tag `phase-02-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] springdoc-openapi dependency (version compatible with Spring Boot 4.1.1); Swagger UI at
      `/swagger-ui.html`, spec at `/v3/api-docs`
- [x] `@Tag`, `@Operation`, `@ApiResponse` on all three controllers
- [x] `@Schema` examples on the DTOs
- [x] Documented error responses (404/400/409, the `{status, message}` shape)
- [ ] Smoke test additions: `/v3/api-docs` returns 200 and contains every `/api` path;
      `/swagger-ui.html` returns 200 or redirects
- [ ] Testing protocol run in full + docs/test-reports/phase-03.md
- [ ] README section, decisions.md, RECENT.md rotation, tracker → 🔵, PR raised

## Last test run
- 2026-09-21: manual spec check against a running app — 12 operations across 7 `/api` paths, each
  with a summary; 2xx schemas intact; 400/404/409 all point at `ApiError`; 9 component schemas.
- baseline on `main`: 79 tests green, smoke 34/34

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ⬜)
- springdoc 3.1.1 (first GA line built on Boot 4); code-first not contract-first;
  document metadata as an `OpenAPI` bean, not `@OpenAPIDefinition`; validation constraints are
  not restated in `@Schema` because springdoc already reads them.

## Open note
Chrome's site permissions block `localhost`, so Swagger UI could not be opened in a browser from
this session. Verified over HTTP instead: `/swagger-ui.html` → 302 to `/swagger-ui/index.html`
(200), and its bundle, CSS and initializer all serve. Visual confirmation is part of the user's
PR review.

## Next action
Add the smoke-test checks (`/v3/api-docs` 200 + every `/api` path present; `/swagger-ui.html`
200-or-redirect), then write the web-slice test for the spec, then run the testing protocol.
