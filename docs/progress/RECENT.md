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

## Phase 23: Distributed Tracing (tag: phase-23-complete, PR #37)
**What exists now:** all six services trace with OpenTelemetry and export OTLP to **Tempo 3.0.3**
(18 containers). One checkout = ONE trace: gateway -> app -> inventory (HTTP), inventory -> catalog
and app -> outbox relay -> Kafka -> notification (async). ECS logs carry `traceId`; Grafana links
Loki <-> Tempo both ways. Smoke **327 / 0 / 0** cold on the WSL2 workstation.
**Key code:** `common/.../tracing/TracingConfig` (servlet: no `/actuator` observations) and
`gateway/TracingConfig` (reactive twin); `ecomdemo-app/.../messaging/internal/OutboxTracing` (stores
and restores the `traceparent` through `outbox_event.trace_parent`, V17) and
`OutboxObservationConfig` (drops ONLY the relay's `@Scheduled` tick).
**Config & infrastructure:** `common/src/main/resources/ecomdemo-observability.properties`, imported by
every service (sampling, Kafka observations, ECS fields, `spring.security` observations off).
Compose sets `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://tempo:4318/v1/traces` and
`TRACING_SAMPLING_PROBABILITY=1.0` (code default 0.1, parent-based). Tempo API on host **3200**.
**Prometheus is on host port 19090** (user's decision; Windows reserves 9014-9113).
The gateway has `spring.reactor.context-propagation: auto`.
**Tests:** OutboxTracingTest (real OTel SDK), OutboxObservationConfigTest; smoke section "Distributed
tracing" (14 checks: own `traceparent` -> Tempo trace with >=4 services, gateway continues the caller's
span, relay + consumer spans, unsampled leaves no trace, Loki finds the trace id, Grafana datasources).
**Gotchas:** no endpoint set = no exporter (tests trace nothing). Kafka observations are OFF by default
in Spring Kafka. Each service flushes spans on its own 5 s timer - poll for every hop you assert.
`management.observations.enable.<prefix>` matches NAMES only (all `@Scheduled` share one). OTel sets
traceparent flags `03`, not `01` - read the sampled bit. `ModularityTest` regenerates `docs/modules/*`.
**Follow-ups (not done):** Tempo metrics-generator (RED metrics, service graph); retention/object
storage; the gateway writes no request log; a smoke check for the gateway's `traceId` (verified by hand).

## Phase 22: Resilience (tag: phase-22-complete, PR #35)
**What exists now:** every `ecomdemo-app` → catalog-service call goes through
Retry(CircuitBreaker(Bulkhead(HTTP call with a timeout))). catalog-service down: add-to-cart is a 503
+ Retry-After in ~0.6 s, the breaker opens after 5 failed calls (refusals ~20 ms), CHECKOUT STILL
SUCCEEDS (cart snapshot), and it recovers on its own ~10 s after a restart. Grafana dashboard
"EcomDemo Resilience". Smoke **313 / 0** cold on the WSL2 workstation.
**Key code:** `ecomdemo-app/.../resilience/` (`ResilientCatalog`, `CatalogResilienceConfig` - a static
BeanPostProcessor wrapping the HTTP `CatalogClient` IN PLACE, so the ITs' @Primary fake is untouched);
`common/.../clients/catalog/CatalogProperties` (connect/read/bulk-read timeouts, two RestClients);
`common/.../shared/ServiceUnavailableException` → 503 in `GlobalExceptionHandler`.
**Config & infrastructure:** `resilience4j.*.instances.catalog.*` and `ecomdemo.catalog.*-timeout` in the
app's application.properties (250 ms connect, 500 ms read, 30 s bulk; 2 attempts; window 10 / min 5 /
50 %; open 10 s; bulkhead 20). `resilience4j.version` 2.4.0 in the parent. `scripts/failure-demo.sh`.
**Tests:** ResilientCatalogTest (loads the SHIPPED resilience4j.* properties into R4j's own
auto-config; includes the worst-case budget sum), CatalogClientTimeoutTest (real slow HttpServer),
DashboardMetricsTest (+ resilience series from the real binders); smoke §Resilience (18 checks).
**Gotchas:** a STOPPED container is not refused - cached IP → SYN unanswered (connect timeout); expired
cache → name resolution hangs ~12 s (the connect timeout does NOT cover DNS; the read timeout bounds it).
Timeouts compose: attempts × read timeout + backoff must fit the budget. `resilience4j-micrometer` must
not be test-scoped. Windows reserved 9022-9121 (WinNAT) → Prometheus 9090 failed; use PROMETHEUS_PORT.
A VM pause made one `verify` take 86 min.
**Follow-ups (not done):** cold-start poison-message partition stall (seen once in 3 cold runs); the
gateway has no breaker on its own `/api/products` route; notification-service at 97 % of its 320M cap;
resilience for the inventory calls (checkout's real dependency); Phase 21 recorded no decisions.
