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

## Phase 14: Batch Processing (tag: phase-14-complete, PR #16)
**What exists now:** Two Spring Batch jobs. `productImportJob` reads a product CSV uploaded by an
ADMIN, validates each row, upserts by product name, skips bad rows up to a limit and writes them
to an error file beside the upload; it is restartable. `salesReportJob` writes a CSV of one day's
order count, revenue and best sellers, every night at 02:00. 276 tests (229 + 47), smoke test 156
checks. Schema V8.
**Key code:** `batch/BatchConfig` — **the critical one**: Spring Batch 6 defaults to
`ResourcelessJobRepository` (in memory), so this extends `DefaultBatchConfiguration` (which is
what makes Boot's auto-config back off) and supplies a `JdbcJobRepositoryFactoryBean`.
`batch/ProductImportJobConfig` (chunk step, `chunk(size).transactionManager(...)` — the 6.0 API,
not the deprecated two-arg form; `@StepScope` reader/writer/skip-listener reading
`#{jobParameters['inputFile']}`), `ProductImportProcessor` (validation + upsert via
`findFirstByNameOrderByIdAsc`), `ProductUpsertWriter` (saves the chunk; evicts the Phase 13 caches
in `afterStep`, never inside the transaction), `RejectedRowRecorder` (SkipListener -> error file),
`SalesReportJobConfig` (a TASKLET step for the summary, a CHUNK step over a `JdbcCursorItemReader`
for the table), `SalesReportScheduler` (`@EnableScheduling` lives here), `BatchService`,
`BatchController` (`/api/admin/batch/**`).
**Config & infrastructure:** New dep `spring-boot-starter-batch` (Spring Batch 6.0.5).
`spring.batch.job.enabled=false`. `ecomdemo.batch.{directory,chunk-size,skip-limit,sales-report-cron}`.
Flyway **V7** = Spring Batch's own `schema-postgresql.sql` verbatim (Boot 4 has no
`initialize-schema` property at all); **V8** = a non-unique index on `product.name`.
`/api/admin/**` is ADMIN-only by prefix in `SecurityConfig`. Dockerfile creates
`/var/lib/ecomdemo/batch` owned by the runtime user and sets `BATCH_DIR`; compose mounts the
`batch-data` named volume there. `spring.servlet.multipart.max-file-size=16MB`. Tests point
`ecomdemo.batch.directory` at `./target/...`.
**Tests:** `ProductImportProcessorTest`, `RejectedRowRecorderTest`, `BatchControllerTest`,
`BatchJobRepositoryTest` (asserts ROWS in the BATCH_ tables), `SalesReportScheduleTest` (cron set
to every second; costs a context of its own), `ProductImportJobIT` (10,000 rows + restart),
`SalesReportJobIT`. `FlywayMigrationTest` and `OpenApiDocumentationTest` extended.
Test report: `docs/test-reports/phase-14.md`.
**Gotchas:** (1) The in-memory JobRepository above — every behavioural test passed against it
while the BATCH_ tables stayed empty; only an assertion about rows caught it, and the tell was
every execution reporting `id=1`. (2) A restart resumes from the last COMMIT, so rows that were
SKIPPED are behind it and are NOT reconsidered however well the file is fixed; they come back by
re-importing, which is safe because the import upserts. (3) The 6.0 chunk step gives
`rollbackCount == 0` on a run with skips (no item-by-item replay) and wraps a skip-limit failure
in `FatalStepExecutionException: Unable to process chunk` — hence `JobExecutionResponse` now
reports the whole cause chain. (4) The batch directory cannot live under `/app`: root-owned, and
the app is not. (5) Spring's cron has SIX fields.
**Follow-ups (not done, out of scope):** a manual trigger endpoint for the sales report; 202 +
polling once jobs run long enough to time out a request; a supplier SKU in a unique column
instead of keying the upsert on a non-unique name; restart across a container replacement is
inferred from where the state lives, not tested; `@Scheduled` fires in every instance, so a real
deployment wants a leader election rather than a JobRepository collision.

## Phase 13: Caching (tag: phase-13-complete, PR #15)
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
