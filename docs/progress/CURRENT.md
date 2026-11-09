# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-09
- **Fix:** KI-036 (no metrics cardinality budget is asserted). The owner: "continue", on the recommendation to take KI-036
- **Branch:** fix/ki-036-meter-tag-budget (cut from `main` at `aa5da39`)
- **Step:** PR_OPEN (#117), waiting on CI
- **Waiting for user:** no
- **History:** KI-062 (PR #115) merged as `aa5da39`, merge-verified 2026-11-09 (`./mvnw -B test` 0 failures, 29 Docker-only errors; the compose burst had 0 x 503). The web Dependabot hold-back (branch `chore/dependabot-hold-opentelemetry`) is obsolete: web PR #68 moved to OpenTelemetry JS 3; not opened. Tags for the owner (403 here): `ki-062-fixed` `aa5da39`, `ki-083-fixed` `e836bd3`, `ki-082-fixed` `eeaa277`, `ki-032-fixed` `9ce4adf`, `ki-031-fixed` `d939733`, `ki-073-fixed` `b712df7`, `ki-076-fixed`/`ki-079-fixed` `f68f00b`, `ki-080-fixed` `f872e3d`, `ki-078-fixed` `d69b738`, `ki-077-fixed` `8d36b19`, `ki-074-fixed`/`ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`

## Checklist
- [x] Re-checked: meters tag outcome/type/key with closed sets; outbox ids are on a span; no lowCardinalityKeyValue; Boot's max-uri-tags at its default
- [x] Guard: `MeterTagBudgetTest` (ecomdemo-app; reads every module's main sources): meter tag keys and low-cardinality keys must be in a documented budget; a self-test proves it catches a per-entity tag (literal, constant, low-cardinality) and ignores span tags; max-uri-tags not raised. 3 of 3 pass; no defect to reproduce (a gap): the self-test is the red case
- [x] Docs (KNOWN_ISSUES, decisions); full unit run 0 failures; PR #117
- [ ] CI green; STOP for review

## Next action
Watch PR #117 CI; when green, STOP for the owner's review.

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
