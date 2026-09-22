# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 8: Spring Security
- **Branch:** feature/phase-08-spring-security
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Phase 07 merge verification (passed 2026-09-22)
PR #7 MERGED with a merge commit (fe2e02c, 2 parents: cf50c2f + ae20fd2); branch is an ancestor
of `main`; no commits and no file diffs between branch and `main`; local and remote branches
intact; every "What you'll implement" item present in `main` (`PostgresContainerConfig` with
`@ServiceConnection`, `IntegrationTest` base class, `ProductApiIT`/`CartApiIT`/`OrderApiIT`,
`application-it.properties`, Surefire/Failsafe split in `pom.xml`, `docs/test-reports/phase-07.md`);
`./mvnw clean verify` on `main` -> BUILD SUCCESS, Surefire 120 tests + Failsafe 15 tests,
0 failures; the app on `main` started with "Successfully validated 4 migrations" / "Current
version of schema public: 4"; `scripts/smoke-test.sh` -> 78 passed, 0 failed, 0 skipped, 0 ERROR
in the app log; tag `phase-07-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] `customer` feature: registration (BCrypt), profile, `users` table via Flyway, admin seeded by a migration
- [x] Roles `CUSTOMER` and `ADMIN` with HTTP Basic authentication
- [x] Rules: product reads public, product writes ADMIN, cart and orders CUSTOMER
- [x] Cart per user (replaces the shared cart), orders owned by the user
- [x] `@PreAuthorize` so users only see their own orders
- [x] 401 and 403 in the standard error format
- [x] `@WithMockUser` tests covering allowed and denied access per role
- [x] Smoke test additions (anon read 200, anon cart 401, customer create product 403, admin 201,
      customer A cannot read customer B's order, full flow as a logged-in customer)
- [x] Testing protocol run in full + docs/test-reports/phase-08.md
- [x] README section, decisions.md, RECENT.md rotation (Phase 06 archived), tracker -> 🔵
- [ ] PR raised

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS. Surefire 163 (was 120), Failsafe 23 (was 15),
  0 failures, 0 skipped. Run four times, all green (36.1 / 31.4 / 31.8 / 33.3 s).
- 2026-09-22: `./mvnw clean test` -> 163 tests, 11.4 s, zero "Creating container" lines: the fast
  suite still needs no Docker.
- 2026-09-22: the app started against the live Phase 7 database and applied V5 and V6
  incrementally ("Successfully applied 2 migrations ... now at version v6"); a restart then
  re-validated 6 migrations. 0 ERROR in the log.
- 2026-09-22: `scripts/smoke-test.sh` -> 110 passed, 0 failed, 0 skipped. Run three times (twice
  on one instance, once after a restart, which satisfied the persistence check).
- 2026-09-22: manual checks - 401 carries no WWW-Authenticate, a registration body with
  "role":"ADMIN" still yields CUSTOMER, that account gets 403 on POST /api/products, duplicate
  registration is 409, /v3/api-docs declares basicAuth. Probe account deleted afterwards.

## Open issues / blockers
- none.

## Decisions this phase (copied to docs/decisions.md ⬜)
- Two migrations, not one: V5 adds `users` + the seeded admin, V6 attaches cart and orders.
- ADMIN is refused on /api/cart and /api/orders (403). Being an admin does not imply being a
  customer; the phase file says those endpoints "require CUSTOMER" and this takes it literally.
- Reading somebody else's order is 403 (`@PostAuthorize`), not 404. The phase file allows either.
- `@WebMvcTest` does NOT pick up our SecurityConfig; @WithSecurityRules imports it so the slices
  test the real rules instead of Boot's fallback "deny everything" chain.
- ApiErrorWriter injects Jackson 3's `tools.jackson.databind.json.JsonMapper` — Boot 4 defines no
  `com.fasterxml.jackson.databind.ObjectMapper` bean even though Jackson 2 is on the classpath.
- V6 deletes the existing cart rows (scratch state, no correct owner) but keeps existing orders
  and attributes them to the seeded admin; orders.user_id is ON DELETE RESTRICT.

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING, schema at **v4**.
`docker start ecomdemo-postgres` if it is down. The application is stopped, no stray Java
processes. Docker Desktop must stay running (Testcontainers needs it for `./mvnw verify`).

## Next action
Push the branch and raise the PR (`gh pr create --base main`), then STOP for the user's review.
