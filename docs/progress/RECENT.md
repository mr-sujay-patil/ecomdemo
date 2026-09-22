# Recent Phase Summaries (rolling window: last 2 phases)

> Newest first. When a third summary is added, move the oldest to `docs/progress/archive/phase-XX-summary.md`. Maximum ~30 lines per summary: facts only, no narrative.

<!-- TEMPLATE
## Phase XX: <Title> (tag: phase-XX-complete, PR #N)
**What exists now:** <1–3 lines describing the system after this phase>
**Key code:** <packages and classes that matter next>
**Config & infrastructure:** <profiles, env vars, ports, containers, and how to run>
**Tests:** <new test classes, smoke test additions>
**Gotchas:** <anything surprising the next phase must know>
**Follow-ups (not done, out of scope):** <suggestions deferred to later phases>
-->

## Phase 13: Caching (tag: pending, PR: pending)
**What exists now:** The catalogue is served from Redis. `GET /api/products` and
`/api/products/{id}` are `@Cacheable`; create/update/delete keep the cache honest. Per-cache TTL
(product 10 min, listing 2 min), JSON values typed per cache, hit/miss logging, and a
CacheErrorHandler so a Redis outage costs latency rather than availability. 228 tests
(190 + 38), smoke test 136 checks. Schema still V6.
**Key code:** `cache/CacheConfig` (one `JacksonJsonRedisSerializer` per cache typed to what it
holds, TTLs, `CachingConfigurer.errorHandler()`, a `RedisCacheManager` whose `decorateCache`
wraps everything in `LoggingCache`), `cache/CacheNames`, `cache/LoggingCache`.
`ProductService`: `@Cacheable` on `findAll`/`findById`, `@Caching(put=@CachePut, evict=@CacheEvict)`
on `update`, `@CacheEvict` on `create`/`delete`. **`requireProduct` and `save` are deliberately
uncached** - they are the cart/checkout path. `SecurityConfig` now permits `/error`.
**Config & infrastructure:** New deps `spring-boot-starter-cache` + `spring-boot-starter-data-redis`
(Lettuce). `compose.yaml` gains a `cache` service (`redis:8-alpine`, `--save "" --appendonly no`,
`maxmemory` + `allkeys-lru`, no volume, healthcheck, app `depends_on: service_healthy`); the app
reaches it at `cache:6379`. `application-dev.properties` gets `spring.data.redis.*` with 2s
timeouts. `test` profile: `spring.cache.type=none`. `it` profile: `spring.cache.type=redis`.
**Tests:** +8 `cache/CacheApiIT`, all proving behaviour by changing the database BEHIND the cache
with direct SQL and checking which value comes back. New `support/RedisContainerConfig`
(`GenericContainer` + `@ServiceConnection(name="redis")`) imported by `IntegrationTest`.
Test report: `docs/test-reports/phase-13.md`.
**Gotchas:** A generic serializer with Jackson default typing wrote a root-level List as a bare
array and then demanded a type id on read - every cached listing read failed. Per-cache types fix
it AND remove the deserialization-gadget risk. That 500 reached clients as **401**, because Spring
forwards to `/error` and the forward goes through the filter chain - latent since Phase 8. The
default CacheErrorHandler rethrows, so a cache bug became an outage. `CacheManager.clear()` logged
success while the keys survived (`@CacheEvict` on explicit keys is fine); tests delete keys
directly. Declaring Redis anywhere but the shared `IntegrationTest` config would fork a second
PostgreSQL too - verified one of each across the whole Failsafe run.
**Follow-ups (not done, out of scope):** cache hit-rate metrics - Phase 15. Making the browsing
view's stock accurate without the evict-before-commit race. Redis `requirepass` and a replica.
Redis data types beyond string-with-TTL (hashes, sorted sets, streams) need a RedisTemplate.

## Phase 12: Code Quality (tag: phase-12-complete, PR #14)
**What exists now:** Coverage is measured across BOTH suites and the project passes a quality
gate. JaCoCo runs two agents (Surefire and Failsafe fork separate JVMs) and merges the exec files
at `verify`: 96.1% overall, 97.4% line, 81.0% branch. SonarQube Community runs in its own compose
stack; the first analysis found 16 issues / 92 min debt / reliability D / security D, and the
project now reports **0 bugs, 0 vulnerabilities, 0 smells, 0 debt, A/A/A, QUALITY GATE OK**.
220 tests and the 125-check smoke test are unchanged. Schema still V6.
**Key code:** `pom.xml` — jacoco 0.8.15 with `prepare-agent` + `prepare-agent-integration` +
`merge` + `report` (declared AFTER failsafe so the verify-phase executions run in the right
order), Surefire/Failsafe argLines reading `@{jacocoUnitArgLine}` / `@{jacocoItArgLine}` with
empty defaults, `sonar.coverage.jacoco.xmlReportPaths` -> the MERGED xml, `sonar.coverage.exclusions`
for `EcomdemoApplication` and `**/dto/**`, sonar-maven-plugin 5.8.0.7211 pinned and unbound.
`compose.sonar.yaml` (sonarqube + its own postgres, three named volumes, a status-endpoint
healthcheck). `scripts/sonar-setup.sh` — the quality gate as code, idempotent.
**Config & infrastructure:** No application change. New: `compose.sonar.yaml`,
`scripts/sonar-setup.sh`. Quality gate "EcomDemo way" = Sonar's four defaults +
`new_reliability_rating` and `new_security_rating` at A; every condition on NEW code.
**Tests:** No test added or removed. Fifteen Sonar findings fixed in place — the real bug was
`new SecureRandom()` per call in `JwtConfig`; `OrderAuditService.record` -> `recordAttempt`;
`throws Exception` dropped from `securityFilterChain` (Spring Security 7 no longer declares it,
confirmed by compiling); `RestTemplateBuilder.rootUri` (deprecated for removal) -> a
`DefaultUriBuilderFactory`; five `assertThatThrownBy` lambdas narrowed to one throwing call; four
minor test smells. Test report: `docs/test-reports/phase-12.md`.
**Gotchas:** `sonarqube:lts-community` still resolves to 9.9 and dies mid-migration against
PostgreSQL 18 — pin an exact `*-community` build; the LTA tags are paid-tier only. A literal
`<argLine>` OVERRIDES JaCoCo's injected one, so coverage silently reads 0%; `@{...}` late
evaluation is the fix. A Sonar issue resolved as "Accepted" lives only in the server database and
comes back when the volume is wiped — `@SuppressWarnings("java:S4502")` puts the decision in Git.
Writing `scripts/sonar-setup.sh` surfaced three shell bugs worth remembering: SonarQube answers a
bad password with 401 and an EMPTY body; `x="$(fn)"` runs `fn` in a subshell so globals it sets
are discarded; and `curl` needs `-G --data-urlencode` for a GET parameter containing a space.
**Follow-ups (not done, out of scope):** SonarQube Cloud + PR decoration so the gate actually
blocks a merge — optional in the phase file, deliberately skipped, and the obvious next step.
Raising branch coverage (81%) rather than line coverage. Image scanning — Phase 31.
