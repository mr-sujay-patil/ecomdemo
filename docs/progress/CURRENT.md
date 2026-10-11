# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-11
- **Fix:** KI-067 and KI-068 (remaining half): `scripts/smoke-test.sh` gates container sections on `command -v docker` before `ctr_exec` (kubectl on k8s), so they SKIP on a cluster with no docker CLI; and no workflow runs `scripts/test-smoke-*.sh`
- **Branch:** fix/ki-067-068-smoke-docker-gates (cut from `main` at `abed84a`); grouped per workflow rule 11 (both are review findings on the smoke test's helpers; the user said "proceed")
- **Step:** BRANCHED
- **Waiting for user:** no
- **History:** PR #92 (KI-064, KI-066 and KI-068's weak test) merged at `abed84a`; the owner pushes `ki-064-fixed` and `ki-066-fixed` (tag pushes are refused here, 403)

## Checklist
- [ ] Regression test (fails first): a platform-aware gate helper in `scripts/smoke-clients.sh`, tested with fakes on a controlled PATH (k8s, no docker: the pod is probed through `ctr_exec`), plus a guard that no `command -v docker` gate sits in front of `ctr_exec` in `smoke-test.sh`; mutation check
- [ ] Fix: those sections (and the container discovery) gate on the helper; compose behaviour unchanged; what the checks assert unchanged
- [ ] CI: `ci.yml` runs every `scripts/test-smoke-*.sh` (cheap, bash and coreutils only)
- [ ] `bash -n`, all `scripts/test-smoke-*.sh`, `./mvnw -B clean verify`
- [ ] Docs: KNOWN_ISSUES (fixed), decisions [Fix KI-067, KI-068]
- [ ] Push, PR (label `run-smoke`), CURRENT to PR_OPEN

## Next action
Write the regression test first and show it red on this tree (see the checklist), then implement.

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
