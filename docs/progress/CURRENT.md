# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-27
- **Phase:** 23 — Distributed Tracing (OpenTelemetry + Tempo)
- **Branch:** feature/phase-23-tracing (cut from `main` at `c232a27`)
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Fix-branch merge verification (PR #36) — PASSED, no tag (not a phase)
Merged as a merge commit `c232a27` (2 parents); 0 missing commits, 0 diffs, branch alive; CI green;
`verify` on `main` BUILD SUCCESS (702 test cases, stack down); cold smoke **313/0/0**. The user's two
manual steps are done (`.env` memory pins gone, stash dropped).

## Checklist (from the phase file's "What you'll implement")
- [ ] Tracing in all six services: Micrometer Tracing → OTel bridge → OTLP/HTTP to Tempo
- [ ] Propagation over HTTP: gateway → services, app → catalog/inventory/customer (RestClient)
- [ ] Propagation over Kafka: template + listener observation in app, inventory, notification,
      catalog (catalog's hand-built factory too); the OUTBOX stores the `traceparent` and the relay
      restores it, so the async hop stays in the checkout's trace
- [ ] Tempo in Compose + Grafana Tempo datasource
- [ ] Trace IDs in logs: ECS JSON in all six services; Loki derived field → Tempo; Tempo → Loki
- [ ] Sampling configuration (parent-based, probability from env; 1.0 in compose)
- [ ] Tests + smoke: a checkout's trace in Tempo's API with spans from ≥ 4 services
- [ ] README, decisions, test report, RECENT rotation, tracker 🔵

## Planning decisions
- Tempo **3.0.3** single binary (`-target=all`), local storage; no metrics-generator (a suggestion).
- Services export straight to Tempo (`tempo:4318`), not via Alloy: one hop fewer to learn/debug.
- The smoke test sends its OWN W3C `traceparent` (sampled) so it knows the trace ID to query.

## Next action
Implement the checklist top to bottom. Start by adding `spring-boot-starter-opentelemetry` to
`ecomdemo-app` and reading the resolved Boot 4.1.1 tracing/OTLP property names from the jars.

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
