# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 13: Caching
- **Branch:** feature/phase-13-redis
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Phase 12 merge verification (passed 2026-09-22)
PR #14 MERGED with a merge commit (1b03f5f, 2 parents: 5b50c6b + 62744b0); branch is an ancestor
of `main`; no commits and no file diffs between branch and `main`; branches intact;
`compose.sonar.yaml`, `scripts/sonar-setup.sh`, `docs/test-reports/phase-12.md` and the JaCoCo
configuration all present in `main`. **CI on `main` green** (run 35702256606, both jobs) and the
image published as `latest` + `sha-1b03f5f`. `./mvnw clean verify` -> 190 + 30, 0 failures,
0 skipped; coverage 97.3% line / 81.0% branch. `scripts/smoke-test.sh` against the compose stack
-> 125 passed, 0 failed, 0 skipped, 0 ERROR. Tag `phase-12-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] Redis in Compose
- [x] `@Cacheable` on product reads, `@CacheEvict` / `@CachePut` on writes
- [x] JSON serialization with a TTL per cache
- [x] Hit/miss logging
- [x] A Testcontainers Redis test
- [x] Repeated reads skip the database and updates invalidate the cache (the "Done when")
- [x] Smoke test additions (after a product read the cache key exists in Redis; updating the
      product evicts or refreshes it)
- [x] Testing protocol run in full + docs/test-reports/phase-13.md
- [x] README section, decisions.md, RECENT.md rotation (Phase 11 archived), tracker -> 🔵
- [ ] PR raised, CI green

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS, Surefire 190 (unchanged) + Failsafe 38
  (was 30), 0 failures, 0 skipped. Exactly ONE redis and ONE postgres container for the whole
  Failsafe run (counted from the log) - the shared IntegrationTest context held.
- 2026-09-22: `./mvnw clean test` -> 190 tests, 0 "Creating container" lines. The Phase 7
  Docker-free promise still holds (`spring.cache.type=none` in the test profile).
- 2026-09-22: `scripts/smoke-test.sh` against the compose stack -> 136 passed (was 125), 0 failed,
  **0 skipped** - so every cache check really ran against Redis.
- 2026-09-22: graceful degradation verified by hand: `docker compose stop cache`, then
  `GET /api/products` -> 200 with "cache GET failed ... falling through to the database".

## Open issues / blockers
- none. All three opening concerns were handled and are covered by tests: `requireProduct` is
  uncached and `checkoutIgnoresTheCache` proves the boundary; serialization is per-cache typed
  and `bigDecimalKeepsItsScale` proves the round trip; one container of each was counted.
- Worth carrying forward: `CacheManager.clear()` logged success while the keys survived
  (`@CacheEvict` on explicit keys is fine, and that is all the application uses).

## Decisions this phase (copied to docs/decisions.md ✅ — 11 entries)
- Only the catalogue reads are cached; `requireProduct` (cart/checkout) never is.
- DTOs are cached, never entities.
- One JSON serializer per cache, typed — NOT generic + Jackson default typing (which failed to
  round-trip AND is a deserialization gadget).
- A CacheErrorHandler logs and falls through, so a Redis outage costs latency not availability.
- Every cache has a TTL; `productService.save` evicts nothing (evict-before-commit race).
- `/error` is permitted, because a 500 on a public endpoint was arriving as 401.
- Redis container declared in the shared IntegrationTest config; `spring.cache.type=none` in the
  test profile.
- Redis runs with no persistence, a maxmemory ceiling and allkeys-lru.

## Environment left behind
Docker Desktop RUNNING. Application stack up and healthy (`ecomdemo-app`, `ecomdemo-db`), schema
v6. SonarQube stack (`ecomdemo-sonarqube`, `ecomdemo-sonar-db`) also up at http://localhost:9000;
stop it with `docker compose -f compose.sonar.yaml down` if the memory is wanted back. The
pre-compose container `ecomdemo-postgres` is stopped, not deleted. `.env` holds a real JWT_SECRET
and is gitignored. No stray Java processes.

## Still outstanding (user, not blocking)
- **`required_status_checks` on `main` is `null`.** CI reports but does not block. Phase 11's
  stated manual step, still undone.

## Next action
Raise the PR (`gh pr create --base main`), wait for CI to go green, then STOP for the user's
review. NOTE: `required_status_checks` is now ENFORCED on `main` with `strict: true`, so the PR
may also need `gh pr update-branch` before it can merge — confirm rather than assume.
