# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 7: Integration Testing (Testcontainers)
- **Branch:** feature/phase-07-testcontainers
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #7 — raised, awaiting review
- **Waiting for user:** YES — review and merge PR #7, then say `merged, continue`

## Phase 06 merge verification (passed 2026-09-22)
PR #6 MERGED with a merge commit (cf50c2f, 2 parents: 67d3453 + 344c957); branch is an ancestor of
`main`; no commits and no file diffs between branch and `main`; local and remote branches intact;
every "What you'll implement" item present in `main` (`OrderPlacementService`, `OrderAudit*`,
`@Version` on `Product`, `readOnly` on the read services, `REQUIRES_NEW` audit,
`V4__add_product_version_and_order_audit.sql`, `ConcurrentCheckoutTest`, the smoke test's race
section, `docs/test-reports/phase-06.md`); `./mvnw clean verify` on `main` -> BUILD SUCCESS,
120 tests, 0 failures; the app on `main` started with "Successfully validated 4 migrations" /
"Current version of schema public: 4"; `scripts/smoke-test.sh` -> 78 passed, 0 failed, 0 skipped,
0 ERROR in the app log (only springdoc's 2 advisory WARNs); tag `phase-06-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] The Testcontainers PostgreSQL module with `@ServiceConnection`
- [x] One HTTP-level integration test per feature
- [x] A shared, reusable container configuration
- [x] Surefire for unit tests and Failsafe for `*IT.java` tests
- [x] Testing protocol run in full + docs/test-reports/phase-07.md
- [x] README section, decisions.md, RECENT.md rotation (Phase 05 archived), tracker -> 🔵
- [x] PR raised (#7)

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS. Surefire 120 tests, 0 failures (unchanged);
  Failsafe 15 tests, 0 failures (ProductApiIT 5, CartApiIT 6, OrderApiIT 4). One
  `postgres:18-alpine` container for the whole IT run; PostgreSQL 18.6; the log shows the
  optimistic lock firing once on a Tomcat thread, so the race really ran over HTTP. Run four
  times in a row, all green (16.9s / 17.0s / 16.0s / 18.4s).
- 2026-09-22: `./mvnw clean test` -> 120 tests, 8.7s, zero "Creating container" lines and no
  *ApiIT class run: the fast suite still needs no Docker.
- 2026-09-22: a throwaway `TemporarilyFailingIT` proved `failsafe:integration-test` records a
  failure without stopping the build and `failsafe:verify` is what fails it. File deleted.
- 2026-09-22: the app started from the jar (dev profile, real PostgreSQL) -> "Successfully
  validated 4 migrations", schema v4, 0 ERROR; `scripts/smoke-test.sh` twice -> 78 passed,
  0 failed, 0 skipped both times. App stopped, no Testcontainers containers left behind,
  `GET /api/products` back to 11.

## Open issues / blockers
- none. One check is ⚠️ manual: "Docker not running fails the build" could not be faked from the
  build (`~/.testcontainers.properties` pins the unix-socket strategy and both DOCKER_HOST
  attempts were ignored). Manual steps are in docs/test-reports/phase-07.md §9.

## Decisions this phase (copied to docs/decisions.md ✅)
- H2 STAYS. The phase file's plan implied it would go; the two suites answer different questions
  (fast "is the Java right?" vs "is this true of PostgreSQL?") and deleting H2 would make the
  inner loop need Docker for every run.
- The container is a `@Bean` with `@ServiceConnection`, not `@Container` + `@DynamicPropertySource`.
- `withReuse(true)` is deliberately NOT enabled: a surviving container keeps the last run's rows.
- Boot 4 moved `TestRestTemplate` to `org.springframework.boot.resttestclient` and registers its
  auto-configuration only via `@AutoConfigureTestRestTemplate`; it also needs
  `spring-boot-restclient` on the test classpath (`RestTemplateBuilder`).

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING, schema at **v4**.
`docker start ecomdemo-postgres` if it is down. The application is stopped, no stray Java
processes. Docker Desktop must stay running from this phase onwards (Testcontainers needs it).

## Next action
STOPPED at the mandatory post-PR stop point. Wait for the user.
- If they say `merged, continue` -> run merge verification (execution-protocol §5) on `main`:
  `./mvnw clean verify` (needs Docker running) and `scripts/smoke-test.sh` (needs the
  `ecomdemo-postgres` container up and the app running), the git-workflow Verification Checklist,
  then tag and push `phase-07-complete`, then start Phase 8
  (`docs/phases/phase-08-spring-security.md`).
- If they say `changes: <feedback>` -> back to IMPLEMENTING on this same branch.
- Do NOT merge unless they say exactly `approved, merge it`.
