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

## Phase 15: Metrics & Monitoring (tag: phase-15-complete, PR #17)
**What exists now:** The application is observable. Actuator publishes health (with separate
liveness and readiness groups), info, metrics and a Prometheus scrape endpoint; three business
meters describe checkout; Prometheus scrapes every 15s and Grafana draws an 11-panel dashboard,
both provisioned from files in `docker/`. One alert rule. 303 tests (241 + 62), smoke test 204
checks. Schema still V8 - this phase adds no migration.
**Key code:** `metrics/CheckoutMetrics` (the three meters, **all registered in the constructor**),
`metrics/MetricNames`, `metrics/CheckoutOutcome` (five tag values incl. a catch-all `error`),
`metrics/MetricsConfig` (the `application` common tag, and a `MeterFilter` denying `/actuator`
URIs). `OrderService.place()` now wraps a private `placeWithRetries()` so the timer covers the
whole retry loop; the catch blocks map exceptions to outcomes, specific-first.
`SecurityConfig` gained four `EndpointRequest` rules.
**Config & infrastructure:** New deps `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
(runtime), and the `build-info` goal on `spring-boot-maven-plugin`. `compose.yaml` gains
`prometheus` (3.7.3, :9090) and `grafana` (12.3.1, :3000), both with health checks; config lives in
`docker/prometheus/{prometheus,alerts}.yml` and `docker/grafana/{provisioning,dashboards}`. The
Dockerfile HEALTHCHECK moved from `/api/products` to `/actuator/health/readiness`.
~25 `management.*` properties in `application.properties`, all commented.
**Tests:** +7 `OrderServiceTest.CheckoutMeters` (a real `SimpleMeterRegistry`, not a mock),
+5 `DashboardMetricsTest` (parses the shipped dashboard and alert files; **verified by mutation**),
+15 `metrics/ActuatorApiIT` (asserts on the scrape TEXT, not the registry).
Test report: `docs/test-reports/phase-15.md`.
**Gotchas:** An absent series is not zero - PromQL over one returns no rows, so a panel reads "No
data" and an alert can never fire; hence constructor registration. A metric name is a public
interface with no compiler behind it, hence `DashboardMetricsTest`. Readiness with the DB down
answers `{"status":"DOWN"}` 503 after **10s** (the driver's connectTimeout), not instantly, and the
container HEALTHCHECK's `--timeout=3s` means it fails by timeout rather than by reading the 503 -
right verdict, different route. `EndpointRequest` moved package in Boot 4
(`org.springframework.boot.security.autoconfigure.actuate.web.servlet`), as did
`MeterRegistryCustomizer` (`org.springframework.boot.micrometer.metrics.autoconfigure`).
**Follow-ups (not done, out of scope):** Alertmanager - nothing delivers the alert anywhere.
`management.server.port` on an internal-only network, which is the real fix for the anonymous
scrape endpoint. Cache hit-rate panels (the Phase 13 follow-up; `cache_gets_total` is published but
not graphed). Batch job metrics on the dashboard. A cardinality budget asserted in a test.

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

