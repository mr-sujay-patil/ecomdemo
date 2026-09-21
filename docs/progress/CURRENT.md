# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 2: Automated Testing (JUnit 5 + Mockito + MockMvc)
- **Branch:** feature/phase-02-testing
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 01 merge verification (passed 2026-09-21)
PR #1 MERGED with a merge commit (85af7b5, 2 parents); branch is an ancestor of `main`;
no commits or file diffs between branch and `main`; remote branch intact;
`./mvnw clean verify` on `main` → BUILD SUCCESS (1 test); `scripts/smoke-test.sh` → 34/34, exit 0;
tag `phase-01-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] Unit tests for every service class, repositories mocked with Mockito
- [x] `@WebMvcTest` controller tests (status codes, JSON body, validation errors)
- [x] `@DataJpaTest` tests for custom queries
- [x] Naming `methodName_condition_expectedResult`, AssertJ assertions, Given/When/Then structure
- [x] Every service method has a success **and** a failure test
- [ ] Testing protocol run in full + docs/test-reports/phase-02.md
- [ ] README test section, decisions.md, RECENT.md rotation, tracker → 🔵, PR raised

## Last test run
- 2026-09-21: service unit tests → 35 (Product 13, Cart 12, Order 10), 0 failures
- 2026-09-21: web slices → 33 (Product 15, Cart 11, Order 7), 0 failures
- 2026-09-21: `./mvnw clean verify` → BUILD SUCCESS, Tests run: 79, Failures: 0, Errors: 0, Skipped: 0

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ⬜)
- (none yet)

## Next action
Run `docs/process/testing-protocol.md` in full (build, cold start, smoke test), write
`docs/test-reports/phase-02.md`, then update the README test section, `docs/decisions.md` and
`RECENT.md`, set the tracker to 🔵, push and raise the PR.
