# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-11
- **Fix:** KI-067 and KI-068 (remaining half): `scripts/smoke-test.sh` gates container sections on `command -v docker` before `ctr_exec` (kubectl on k8s), so they SKIP on a cluster with no docker CLI; and no workflow runs `scripts/test-smoke-*.sh`
- **Branch:** fix/ki-067-068-smoke-docker-gates (cut from `main` at `abed84a`); grouped per workflow rule 11 (both are review findings on the smoke test's helpers; the user said "proceed")
- **Step:** PR_OPEN (PR #93, label `run-smoke`)
- **Waiting for user:** YES: CI on PR #93, then `approved, merge it`
- **History:** PR #92 (KI-064, KI-066 and KI-068's weak test) merged at `abed84a`; the owner pushes `ki-064-fixed` and `ki-066-fixed` (tag pushes are refused here, 403)

## Checklist
- [x] Regression test (fails first): `scripts/test-smoke-gates.sh` runs each gate read out of `smoke-test.sh` with its real `ctr_exec` and fake kubectl/docker (8 failed before; both mutations caught)
- [x] Fix: `ctr_available` in `smoke-clients.sh`; those sections (and the container discovery) gate on the helper; compose behaviour unchanged; what the checks assert unchanged
- [x] CI: `ci.yml` job `smoke-script-tests` runs every `scripts/test-smoke-*.sh` (cheap, bash and coreutils only)
- [x] `bash -n`, all `scripts/test-smoke-*.sh` green; `./mvnw -B clean verify` (874 tests, 0 failures)
- [x] Docs: KNOWN_ISSUES (fixed, PR #93; new KI-069), decisions [Fix KI-067, KI-068]
- [x] Push, PR #93 (label `run-smoke`), CURRENT to PR_OPEN
- [ ] CI green on PR #93, including the compose smoke test and the new `smoke-script-tests` job

## Next action
Wait for CI on PR #93 (with `run-smoke`; the new `smoke-script-tests` job runs for the first time); fix it if red. Then STOP for the owner. After the merge: merge verification; the owner pushes `ki-067-fixed` and `ki-068-fixed`. Open from this fix: KI-069 (duplicated diagnostic line in `test-smoke-guard.sh`).

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
