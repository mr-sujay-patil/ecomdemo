# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-27
- **Phase:** 23 — Distributed Tracing (OpenTelemetry + Tempo)
- **Branch:** feature/phase-23-tracing (cut from `main` at `c232a27`)
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** YES — Docker Desktop's WSL integration dropped mid-session (`docker` not found)

## Fix-branch merge verification (PR #36) — PASSED, no tag (not a phase)
Merged as a merge commit `c232a27` (2 parents); 0 missing commits, 0 diffs, branch alive; CI green;
`verify` on `main` BUILD SUCCESS (702 test cases, stack down); cold smoke **313/0/0**. The user's two
manual steps are done (`.env` memory pins gone, stash dropped).

## Checklist (from the phase file's "What you'll implement")
- [x] Tracing in all six services: `spring-boot-starter-opentelemetry`, shared settings in
      `common/src/main/resources/ecomdemo-observability.properties` (imported by each service)
- [x] Propagation over HTTP — verified live: gateway → catalog → inventory in ONE trace
- [~] Propagation over Kafka: code done (V17 `trace_parent`, `OutboxTracing`, relay template and
      catalog factory observed; `OutboxTracingTest` 6/6 green) — NOT yet verified live
- [x] Tempo 3.0.3 in compose (`docker/tempo/tempo.yaml`) + Grafana Tempo datasource
- [~] Trace IDs in logs: ECS in all six (field `traceId`), Alloy `trace_id` metadata, Loki↔Tempo
      links — not yet verified live; gateway MDC may need `spring.reactor.context-propagation=auto`
- [x] Sampling: parent-based, `TRACING_SAMPLING_PROBABILITY` (0.1 default, 1.0 in compose)
- [~] Smoke section "Distributed tracing" written, NEVER RUN yet
- [ ] Full `verify`, cold smoke ×3, test report, README, decisions, RECENT rotation, tracker 🔵

## Planning decisions
- Tempo **3.0.3** single binary (`-target=all`), local storage; no metrics-generator (a suggestion).
- Services export straight to Tempo (`tempo:4318`), not via Alloy: one hop fewer to learn/debug.
- The smoke test sends its OWN W3C `traceparent` (sampled) so it knows the trace ID to query.
- Not traced: `/actuator/**` (ObservationPredicate, servlet in common + reactive in gateway),
  `spring.security.*` and `tasks.scheduled.*` observations (measured noise in Tempo).
- OTel sets traceparent flags `03` (sampled + W3C L2 "random"), not `01` — tests read the bit.

## Next action
Once `docker info` works again: `docker compose up --build --wait -d`, run `scripts/smoke-test.sh`,
fix the new "Distributed tracing" section until green (expected services in a checkout trace:
gateway-service, ecomdemo, inventory-service, notification-service, probably catalog-service). Then
check the gateway's log lines carry `traceId`; then the full testing protocol.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- VM pauses (check `dmesg | grep TimeSync`), Windows port reservations, ~12 s DNS for a stopped container.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).

## ⚠️ Carried, not fixed (oldest first)
- A failed compensating release leaks a reservation; nothing reconciles it. → Phase 24
- The CSV import is a distributed write with no shared transaction (restartable, idempotent). → Phase 24
- **HS256 with a shared secret** — every service can mint as well as verify.
- `ecomdemo-app` is not yet named `order-service`.
- A correlation ID dies at the hop (only the app logs structured). → Phase 23 fixes this.
- catalog-service has no springdoc; the gateway does no request logging.
- The gateway's `/api/products` route has no breaker; inventory calls have no resilience policy.
- Phase 21 recorded no entries in `docs/decisions.md`; `application.properties` still describes the
  removed customer proxy.
- catalog-service is slow on its first requests after a restart (a half-open trial can exceed 500 ms).
