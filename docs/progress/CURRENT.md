# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 2: Automated Testing (JUnit 5 + Mockito + MockMvc)
- **Branch:** feature/phase-02-testing
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #2 — https://github.com/mr-sujay-patil/ecomdemo/pull/2 (open, awaiting review)
- **Waiting for user:** YES — review and merge PR #2, then say `merged, continue`

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
- [x] Testing protocol run in full + docs/test-reports/phase-02.md
- [x] README test section, decisions.md, RECENT.md rotation, tracker → 🔵, PR raised

## Last test run
- 2026-09-21: service unit tests → 35 (Product 13, Cart 12, Order 10), 0 failures
- 2026-09-21: web slices → 33 (Product 15, Cart 11, Order 7), 0 failures
- 2026-09-21: `./mvnw clean verify` → BUILD SUCCESS, Tests run: 79, Failures: 0, Errors: 0, Skipped: 0
- 2026-09-21: `./mvnw spring-boot:run` → "Started EcomdemoApplication in 1.716 seconds", 0 ERROR/WARN
- 2026-09-21: `BASE_URL=http://localhost:9090 scripts/smoke-test.sh` → 34 passed, 0 failed, exit 0

## Open issues / blockers
- none. (`server.port=9090` appeared from outside this session and was swept into commit 3ee9f5d
  by a blanket `git add -A`; reverted in dd09d10 on the user's instruction. The working tree was
  also switched to `main` mid-phase by something outside this session and switched back — no work
  was lost.)

## Decisions this phase (copied to docs/decisions.md ✅)
- Four test levels, each loading only what it tests; `MockMvcTester` over Hamcrest MockMvc;
  web tests assert JSON not DTOs (BigDecimal scale); `@DataJpaTest` only for hand-written
  `@Query` methods, with `spring.sql.init.mode=never`; ids set reflectively in `TestData`;
  Mockito agent loaded by surefire.

## Next action
STOPPED at the mandatory post-PR stop point. Wait for the user.
- If they say `merged, continue` → run merge verification (execution-protocol §5) on `main`:
  re-run `./mvnw clean verify` and `scripts/smoke-test.sh`, run the git-workflow Verification
  Checklist, then tag and push `phase-02-complete`, then start Phase 3
  (`docs/phases/phase-03-openapi.md`).
- If they say `changes: <feedback>` → back to IMPLEMENTING on this same branch.
- Do NOT merge unless they say exactly `approved, merge it`.
