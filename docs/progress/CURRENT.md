# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-003, compose published every port on all interfaces (databases with default passwords, Redis, Kafka)
- **Branch:** fix/ki-003-compose-loopback-ports (cut from `main` at `091cabe`)
- **Step:** PR_OPEN
- **PR:** #66 https://github.com/mr-sujay-patil/ecomdemo/pull/66
- **Waiting for user:** YES (review of PR #66)

## Merge verification of KI-002 (done 2026-10-06)
PR #65 merged as `091cabe`; CI on `main` all green incl. publish; cold compose smoke on `main` 480/0/0 (also closes
KI-040's owed cold run); tag `ki-002-fixed`. The daily scan (KI-044) has fired: 2026-10-07 11:21 UTC, success.

## Done
- All 23 mappings are `${BIND_ADDRESS:-127.0.0.1}:<host>:<container>` (`compose.yaml` 22, `compose.sonar.yaml` 1);
  `BIND_ADDRESS=0.0.0.0` in `.env` is the opt-out. Chosen over "publish only gateway/Grafana/Prometheus".
- Tests, seen failing first: `scripts/test-compose-ports.sh` (static) and the smoke test's "Published ports" section (live).
- Live: LAN address 172.25.188.147 refused on every probed port, loopback open; Redis with the opt-out was open on the
  LAN address (contrast); Windows `powershell.exe` reaches `localhost:8080`.
- `./mvnw clean verify` exit 0; smoke 482/0/0; `scripts/test-smoke-guard.sh` passes. Not run: Kubernetes (untouched).
- My compose stack is stopped (`docker compose down`, volumes kept).

## Next action
STOP: wait for the user's review of PR #66. After `approved, merge it`: verify the merge (tip in `main`, CI on `main`
incl. scans and publish), tag `ki-003-fixed`. A cold compose smoke on `main` is optional (config-only change, run on this
tree already); do it if no foreign stack is up.
Still open: KI-004..011. `stash@{0}` (KI-045 noise) is obsolete; the user's to drop.

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
