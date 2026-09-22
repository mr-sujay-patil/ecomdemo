# Phase 16 Summary (archived)

> Moved out of `docs/progress/RECENT.md` when Phase 18's summary was added. Nothing is
> deleted; it is simply no longer loaded at session start.

## Phase 16: Centralized Logging (tag: phase-16-complete, PR #18 + follow-up #19)
**What exists now:** The application's console log is ECS JSON when `LOG_FORMAT=ecs` (compose sets
it; a laptop leaves it unset and keeps the pattern layout). Every request carries a correlation ID
in the MDC and in the `X-Correlation-Id` response header, including 401s. Alloy tails the
containers over the Docker socket and pushes to Loki; Grafana has a provisioned Loki datasource
with a derived field and an "EcomDemo Logs" dashboard. 338 tests (268 + 70), smoke test 227
checks. Schema still V8 - this phase adds no migration and no new Java dependency.
**Key code:** `logging/CorrelationId` (header/MDC spellings and the `[A-Za-z0-9_-]{8,64}` rule),
`logging/CorrelationIdFilter` (`@Order(HIGHEST_PRECEDENCE)`, MDC cleared in a `finally`,
`shouldNotFilterErrorDispatch()` false), `logging/RequestLogFilter`
(`HIGHEST_PRECEDENCE + 10`, one line per request, skips `/actuator`, quotes nothing).
**Config & infrastructure:** No new Maven dependency - structured logging is native to Boot since
3.4. Four `logging.structured.*` properties in `application.properties`; `service.version` is
`@project.version@` (Maven resource filtering) and `node-name` is `${HOSTNAME:}`. `compose.yaml`
gains `loki` (3.7.8, :3100) and `alloy` (v1.19.2, :12345, Docker socket mounted `:ro`), plus
`LOG_FORMAT: ${LOG_FORMAT:-ecs}` on the app. Config in `docker/loki/loki.yaml` and
`docker/alloy/config.alloy`; Grafana gains `datasources/loki.yml` and
`dashboards/ecomdemo-logs.json`.
**Tests:** `CorrelationIdFilterTest` (7, MDC lifecycle + log injection), `RequestLogFilterTest`
(6, what must NOT appear), `StructuredLoggingTest` (6, a real ECS document + an Alloy drift
guard), `LoggingStackConfigTest` (8, the four config files against each other),
`CorrelationIdApiIT` (8). Smoke test +23. Test report: `docs/test-reports/phase-16.md`.
**Gotchas:** (1) Neither image can health-check itself - Loki 3.7 is DISTROLESS (no shell, no
wget, and no client subcommand like Prometheus' promtool) and Alloy has bash but no curl/wget; the
readiness check lives in the smoke test instead, and Loki answers 503 for a while after a
recreate. (2) Grafana reads DATASOURCE provisioning only at startup - dashboards reload every 30s,
datasources do not, so a new one needs `docker compose restart grafana`. (3) A dotted MDC key
becomes a NESTED JSON object in ECS output, hence `correlation_id` not `correlation.id`.
(4) `compose.sonar.yaml` shares the Compose PROJECT name, so an Alloy deny-list silently shipped
SonarQube's logs; the pipeline uses an allow-list. (5) Bind-mounted config that did not exist in
the branch you came from gets a NEW inode on checkout, and a running container keeps the old one -
`docker compose up -d --force-recreate <service>` after switching branches.
**KNOWN FAILURE, pre-existing and out of scope:** the smoke check "failed checkout did not touch
stock" fails. Placing an order decrements `product.stock_quantity` in the database but evicts
NEITHER Phase 13 cache, so `GET /api/products/{id}` serves the pre-order stock for up to the 300s
TTL. Reproduced directly: DB 2, API 4. The `order` package contains no cache eviction at all; the
Phase 16 diff touches no product, order or cache class. Awaiting the user's decision on where to
fix it.
**Follow-ups (not done, out of scope):** the stale-cache bug above. Trace IDs and logs-to-traces
(Phase 23 - the derived field already has the shape). Alertmanager still delivers nothing.
Log-based alerting in Loki's ruler. A `prod` profile that raises framework log levels. Multi-line
stack traces are one ECS field already, so nothing to stitch - but that only holds while the log
is JSON.

<!-- Phases 14-15 archived to docs/progress/archive/ -->
