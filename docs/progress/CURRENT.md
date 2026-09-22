# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 9: JWT Authentication
- **Branch:** feature/phase-09-jwt
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Phase 08 merge verification (passed 2026-09-22)
PR #8 MERGED with a merge commit (0af4342, 2 parents: fe2e02c + 4569a3e); branch is an ancestor
of `main`; no commits and no file diffs between branch and `main`; local and remote branches
intact; every "What you'll implement" item present in `main` (`security/` package with
SecurityConfig + AppUserDetails(Service) + CurrentUser + the ApiError 401/403 handlers,
`customer/` with User/Role/UserRepository/CustomerService/CustomerController/dto,
`V5__add_users.sql`, `V6__cart_and_orders_per_user.sql`, `WithSecurityRules`,
`TestAuthentication`, `docs/test-reports/phase-08.md`); `./mvnw clean verify` on `main` ->
BUILD SUCCESS, Surefire 163 + Failsafe 23, 0 failures, 0 skipped; the app on `main` started with
"Successfully validated 6 migrations" / "Current version of schema public: 6";
`scripts/smoke-test.sh` -> 110 passed, 0 failed, 0 skipped, 0 ERROR in the app log;
tag `phase-08-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] `POST /api/auth/login` returns a signed JWT with role claims and a short expiry
- [x] Resource Server validation of the token on every request, key from an environment variable
- [x] Swagger UI configured for Bearer tokens
- [x] Expired and tampered tokens are rejected (the "Done when")
- [x] Smoke test additions (login returns a JWT, the full flow runs with a Bearer token,
      a tampered token -> 401, an expired token -> 401 with a short test expiry)
- [x] Testing protocol run in full + docs/test-reports/phase-09.md
- [x] README section, decisions.md, RECENT.md rotation (Phase 07 archived), tracker -> 🔵
- [ ] PR raised

**Out of scope, suggest only:** the phase file lists a refresh token endpoint as *optional*.
Per hard rule 7 it is NOT being built; it will be suggested in the PR.

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS. Surefire 190 (was 163), Failsafe 30
  (was 23), 0 failures, 0 skipped. Run four times, all green (25.1 / 23.3 / 24.0 / 36.7 s).
- 2026-09-22: `./mvnw clean test` -> 190 tests, 11.0 s, zero "Creating container" lines.
- 2026-09-22: the app started BOTH ways - with JWT_SECRET set (no warning) and without it (the
  generated-key WARN). 0 ERROR in either log; schema validated at v6, unchanged this phase.
- 2026-09-22: `scripts/smoke-test.sh` -> 125 passed, 0 failed, 0 skipped. Run four times: twice
  against the JWT_SECRET instance, once more for re-runnability, once against the generated-key
  instance.
- 2026-09-22: measured on the running app - login 106 ms/call (BCrypt), authenticated GET
  14 ms/call. That gap is what moved from every request to once per login.

## Open issues / blockers
- none. The signing-key question is settled: `JWT_SECRET` when set, otherwise a random key plus a
  loud WARN, and under 32 bytes fails startup. Verified both ways (see "Last test run").

## Decisions this phase (copied to docs/decisions.md ✅ — 14 entries)
- JWT_SECRET with no default in Git; unset -> random key + WARN; under 32 bytes -> startup fails.
- HS256, not RS256: one application issues and accepts. The trade is written down for Phase 20.
- Claims are `sub`, `uid`, `roles` only. No `ROLE_` prefix in the claim; the converter adds it.
- `CurrentUser` reads the Jwt principal. `uid` is read as Number and narrowed (JSON has one
  number type, so it arrives as Integer or Long depending on size).
- `AuthenticationManager` moved to `auth/AuthenticationManagerConfig`: the @WebMvcTest slices
  import SecurityConfig and have no UserDetailsService.
- 401 distinguishes "no token" from "bad token"; login failures never distinguish wrong password
  from unknown username.
- No refresh token (optional in the phase file, out of scope by hard rule 7).

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING, schema at **v6**.
`docker start ecomdemo-postgres` if it is down. The application is stopped, no stray Java
processes. Docker Desktop must stay running (Testcontainers needs it for `./mvnw verify`).
The database holds three accounts: `admin` (seeded by V5) plus `smoke-customer` and
`smoke-customer-b`, created by the smoke test. All passwords are BCrypt hashes.

## Next action
Push the branch and raise the PR (`gh pr create --base main`), then STOP for the user's review.
