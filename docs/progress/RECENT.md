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

## Phase 16: Centralized Logging (tag: pending, PR: pending)
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

<!-- Phase 14 archived to docs/progress/archive/phase-14-summary.md -->
