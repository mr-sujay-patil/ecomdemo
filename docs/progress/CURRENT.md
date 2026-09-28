# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-28
- **Phase:** 23 — Distributed Tracing (OpenTelemetry + Tempo)
- **Branch:** feature/phase-23-tracing (cut from `main` at `c232a27`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #37 https://github.com/mr-sujay-patil/ecomdemo/pull/37
- **Waiting for user:** YES - review of PR #37

## Fix-branch merge verification (PR #36) — PASSED, no tag (not a phase)
Merged as a merge commit `c232a27` (2 parents); 0 missing commits, 0 diffs, branch alive; CI green;
`verify` on `main` BUILD SUCCESS (702 test cases, stack down); cold smoke **313/0/0**. The user's two
manual steps are done (`.env` memory pins gone, stash dropped).

## Checklist (from the phase file's "What you'll implement")
- [x] Tracing in all six services: `spring-boot-starter-opentelemetry`, shared settings in
      `common/src/main/resources/ecomdemo-observability.properties` (imported by each service)
- [x] Propagation over HTTP — verified live: gateway → catalog → inventory in ONE trace
- [x] Propagation over Kafka: V17 `trace_parent`, `OutboxTracing`; verified live (smoke: relay
      span + notification consumer span in the checkout's trace)
- [x] Tempo 3.0.3 in compose (`docker/tempo/tempo.yaml`) + Grafana Tempo datasource
- [x] Trace IDs in logs: Loki finds a checkout's trace id on >=3 services; Loki's trace_id field
      links to Tempo (Tempo->Loki link not smoke-checked). Gateway fixed with
      `spring.reactor.context-propagation=auto`: its 5xx ERROR line had no traceId, now has it and
      Loki finds it by trace_id (verified live by stopping catalog-service; not a smoke check)
- [x] Sampling: parent-based, `TRACING_SAMPLING_PROBABILITY` (0.1 default, 1.0 in compose)
- [x] Smoke section "Distributed tracing" green; poll now waits for gateway AND notification spans
      (one run failed the gateway check because notification's batch reached Tempo first)
- [x] DONE 2026-09-28: `verify` green, cold smoke ×3 = 327/0/0, +1 cold run after the gateway fix
      and port change = 327/0/0 (this machine).
      Test report, README, decisions, RECENT rotation (Phase 21 archived), tracker 🔵: done.

## Planning decisions
- Tempo **3.0.3** single binary (`-target=all`), local storage; no metrics-generator (a suggestion).
- Services export straight to Tempo (`tempo:4318`), not via Alloy: one hop fewer to learn/debug.
- The smoke test sends its OWN W3C `traceparent` (sampled) so it knows the trace ID to query.
- Not traced: `/actuator/**` (ObservationPredicate, servlet in common + reactive in gateway),
  `spring.security.*` observations, and the OutboxRelay's tick only (`OutboxObservationConfig`
  predicate; was the whole `tasks.scheduled` prefix until ultrareview showed it also hid the
  cleanup sweep and nightly sales report).
- OTel sets traceparent flags `03` (sampled + W3C L2 "random"), not `01` — tests read the bit.

## Next action
STOPPED at PR #37, waiting for the user. Do NOT merge unless the user says `approved, merge it`
(then `gh pr merge 37 --merge`, never squash/rebase/--delete-branch). On `merged, continue`: merge
verification per `docs/process/git-workflow.md` step 5 + checklist, `./mvnw clean verify` and a cold
`scripts/smoke-test.sh` on `main`, then tag `phase-23-complete`. On `changes: ...`: back to
IMPLEMENTING on this branch. Prometheus is on host 19090 (compose default; nothing to export).

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
