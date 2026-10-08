# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-009, the README's "Known gaps (closed by later phases)" section is stale
- **Branch:** fix/ki-009-readme-known-gaps (cut from `main` at `43ab02c`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-009 PR)

## Merge verification of KI-008 (done 2026-10-08)
PR #72 merged as `43ab02c`; CI on `main` green incl. scans and publish; tag `ki-008-fixed`.

## Done
- README: the section is now a pointer to `docs/KNOWN_ISSUES.md`; two in-text references point at KI-017.
- KNOWN_ISSUES: KI-009 fixed; KI-048..051 added for the four still-open gaps that were only in the README
  (Sonar gate in CI, smoke test in CI, Redis password, TLS). `decisions.md` [Fix KI-009].
- Docs-only: no build or smoke test run.

## Next action
STOP: wait for review. After `approved, merge it`: wait for checks, merge --merge, verify, tag `ki-009-fixed`. Still open: KI-010, KI-011.

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
