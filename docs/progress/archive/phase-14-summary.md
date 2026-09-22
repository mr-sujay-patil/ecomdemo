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
