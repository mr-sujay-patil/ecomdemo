# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-11
- **Fix:** KI-035: the gateway writes no request log (its "logs no bodies" is an absence, not a decision), and no span carries the correlation id, so an id shown on a 503 the gateway answered itself (`/fallback/catalog`) leads to nothing in Loki or Tempo (it blocks the web team's KI-032)
- **Branch:** fix/ki-035-gateway-request-log (cut from `main` at `f575442`); the owner approved it on 2026-10-11 ("go with backend KI-035")
- **Step:** BRANCHED
- **Waiting for user:** NO
- **History:** PR #92 (KI-064, KI-066, KI-068's weak test) merged at `abed84a`; PR #93 (KI-067, KI-068) merged at `f575442`. The owner pushes `ki-064-fixed`, `ki-066-fixed`, `ki-067-fixed`, `ki-068-fixed` (tag pushes are refused here, 403)

## Checklist
- [ ] Regression tests (fail first): `GatewayRequestLogIT` (one line per exchange, its correlation id in the MDC, nothing quoted: no token, no query string, no body; 401 logged; generated id; actuator skipped; the fallback writes ONE line) and a span test (the gateway's SERVER span for the fallback request carries `correlation_id`)
- [ ] Fix: `RequestLogWebFilter` (WebFilter, HIGHEST_PRECEDENCE + 10, mirrors common's `RequestLogFilter`); `CorrelationIdWebFilter` keeps the id as an exchange attribute; an `ObservationFilter` in the gateway's `TracingConfig` puts it on the server span
- [ ] "Logs for this request" paths include the gateway (Grafana's derived field, the smoke test's correlation checks) where in scope
- [ ] `./mvnw -pl gateway-service -am verify`, then `./mvnw -B clean verify`
- [ ] Docs: KNOWN_ISSUES (fixed), decisions `[Fix KI-035]`, a note on the Phase 23 decision, `CorrelationIdWebFilter`'s javadoc
- [ ] Push, PR (label `run-smoke`), CURRENT to PR_OPEN

## Next action
Write the regression tests in `gateway-service/src/test/java/com/ecomdemo/gateway/` and show them failing; then the fix.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports (and has its own `ecomdemo-backend-readonly_kafka-data` volume: never touch it). kind does not conflict.
- compose ports are loopback-only now; probe from the LAN address (`hostname -I`) to check exposure.
- kind: `docker start ecomdemo-control-plane` revives the cluster; `scripts/k8s-up.sh` loads images but does NOT restart
  pods: `kubectl -n ecomdemo rollout restart deploy` after it, or the pods keep the old code.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy placed inside `scripts/` and delete it after. Never print `.env`.
- Flyway checksums comments too: never edit an applied migration; on a local DB roll back by hand if you must.
- Awaitility polls on its own thread: use `pollInSameThread()` when the condition needs the caller's transaction.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.
