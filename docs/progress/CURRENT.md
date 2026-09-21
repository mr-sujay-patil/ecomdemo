# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 7: Integration Testing (Testcontainers)
- **Branch:** feature/phase-07-testcontainers
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

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
- [ ] Testing protocol run in full + docs/test-reports/phase-07.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 05 archived), tracker -> 🔵
- [ ] PR raised

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS. Surefire 120 tests, 0 failures (unchanged);
  Failsafe 15 tests, 0 failures (ProductApiIT 5, CartApiIT 6, OrderApiIT 4). One
  `postgres:18-alpine` container for the whole IT run; PostgreSQL 18.6; the log shows the
  optimistic lock firing once on a Tomcat thread, so the race really ran over HTTP.

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ⬜)
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
Run `docs/process/testing-protocol.md` in full, then write `docs/test-reports/phase-07.md`, the
README section, `docs/decisions.md` entries and the RECENT.md rotation (archive Phase 05), set the
tracker row 7 to 🔵, push and raise the PR.
