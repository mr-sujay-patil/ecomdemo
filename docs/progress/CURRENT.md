# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 13: Caching
- **Branch:** feature/phase-13-redis
- **Step:** BRANCHED
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
- [ ] Redis in Compose
- [ ] `@Cacheable` on product reads, `@CacheEvict` / `@CachePut` on writes
- [ ] JSON serialization with a TTL per cache
- [ ] Hit/miss logging
- [ ] A Testcontainers Redis test
- [ ] Repeated reads skip the database and updates invalidate the cache (the "Done when")
- [ ] Smoke test additions (after a product read the cache key exists in Redis; updating the
      product evicts or refreshes it)
- [ ] Testing protocol run in full + docs/test-reports/phase-13.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 11 archived), tracker -> 🔵
- [ ] PR raised, CI green

## Last test run
- none yet this phase. Baseline inherited from `main`: 190 Surefire + 30 Failsafe, smoke 125,
  coverage 97.3% line / 81.0% branch, Sonar gate OK with 0 issues.

## Open issues / blockers
- **What NOT to cache is the heart of this phase.** Stock changes on every checkout and is the
  subject of the Phase 6 optimistic-locking race; caching it would resurrect the oversell bug
  the hard way. Plan: cache the catalogue read path only, and make `OrderPlacementService` read
  products through a path that never touches the cache — then prove it with the existing race.
- **Serialization.** The default JDK serializer would make cache entries unreadable and tie them
  to class shape. Use JSON, and check that `BigDecimal` money and `Instant` survive a round trip.
- **The Testcontainers Redis test must not fork a second container set.** Every annotation on
  `IntegrationTest` is part of the context cache key (Phase 7 lesson) — add Redis to that ONE
  base configuration or the suite silently starts paying for a container per class.

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet.

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
Start step 5 (IMPLEMENTING) of the execution protocol on `feature/phase-13-redis`: read
`docs/phases/phase-13-redis.md`, then work the checklist above top-down with small Conventional
Commits, beginning with the Redis service in `compose.yaml` and the cache configuration.
