# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 9: JWT Authentication
- **Branch:** feature/phase-09-jwt
- **Step:** BRANCHED
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
- [ ] `POST /api/auth/login` returns a signed JWT with role claims and a short expiry
- [ ] Resource Server validation of the token on every request, key from an environment variable
- [ ] Swagger UI configured for Bearer tokens
- [ ] Expired and tampered tokens are rejected (the "Done when")
- [ ] Smoke test additions (login returns a JWT, the full flow runs with a Bearer token,
      a tampered token -> 401, an expired token -> 401 with a short test expiry)
- [ ] Testing protocol run in full + docs/test-reports/phase-09.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 07 archived), tracker -> 🔵
- [ ] PR raised

**Out of scope, suggest only:** the phase file lists a refresh token endpoint as *optional*.
Per hard rule 7 it is NOT being built; it will be suggested in the PR.

## Last test run
- none yet this phase. Baseline inherited from `main`: 163 Surefire + 23 Failsafe, smoke 110.

## Open issues / blockers
- **Decide how the signing key is supplied without a manual step.** The phase says "the key from
  an environment variable", and hard rule 9 forbids a secret in Git — but the phase also says the
  user has NO manual steps, so the app must start with `JWT_SECRET` unset. Planned answer:
  read `JWT_SECRET` when present; otherwise generate a random key at startup and log a loud WARN
  saying tokens will not survive a restart. To be confirmed while implementing.

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet.

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING, schema at **v6**.
`docker start ecomdemo-postgres` if it is down. The application is stopped, no stray Java
processes. Docker Desktop must stay running (Testcontainers needs it for `./mvnw verify`).
The database holds three accounts: `admin` (seeded by V5) plus `smoke-customer` and
`smoke-customer-b`, created by the smoke test. All passwords are BCrypt hashes.

## Next action
Start step 5 (IMPLEMENTING) of the execution protocol on `feature/phase-09-jwt`: read
`docs/phases/phase-09-jwt.md`, then work the checklist above top-down with small Conventional
Commits, beginning with the `spring-boot-starter-oauth2-resource-server` dependency, the key
configuration and `POST /api/auth/login`.
