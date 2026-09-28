# Phase 23 Summary (archived from RECENT.md during Phase 25)

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
