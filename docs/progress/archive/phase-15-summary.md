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
