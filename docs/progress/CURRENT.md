# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-21
- **Fix:** KI-078 (the image scan never saves its Trivy cache: `~/.cache/trivy/fanal` is owned by root, so `actions/cache` cannot read it); approved 2026-10-21 (the owner chose "KI-078 Trivy cache")
- **Branch:** fix/ki-078-trivy-cache-owner (cut from `main` at `8d36b19`)
- **Step:** PR_OPEN (PR #102)
- **Waiting for user:** YES: review of PR #102 / `approved, merge it` (after its second CI run restores the cache)
- **History:** KI-077 (PR #101) merged as `8d36b19` and merge-verified 2026-10-21 (branch tip in `main`; push run 291 on `main` green: both scans and the publish). Tags for the owner (403 here): `ki-077-fixed` `8d36b19`, `ki-074-fixed` and `ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`

## Checklist
- [x] KI-078 In progress in `docs/KNOWN_ISSUES.md`
- [x] Regression test red first: `TrivyCacheOwnerTest` failed on `main`'s ci.yml (no `mkdir -p "$HOME/.cache/trivy"`, Trivy as root at `/root/.cache/trivy`)
- [x] Fix in `ci.yml`'s image-scan job: `mkdir -p "$HOME/.cache/trivy"`, `docker run --user "$(id -u):$(id -g)" --group-add "$(stat -c %g /var/run/docker.sock)" -e HOME=/tmp`, mounted at `/cache` with `--cache-dir /cache`; `TrivyCacheOwnerTest` and the Dockerfile tests 4/4 green
- [x] Proof, run 1 (39646793637, `f745077`): all green; image scan's post step "Cache saved with key: trivy-39646793637" (1.0 GB), no `Permission denied`
- [ ] Proof, run 2 (this PR-number commit): "Cache restored from key: trivy-39646793637"
- [x] Docs: KNOWN_ISSUES (Fixed), decisions `[Fix KI-078]` (`docs/security.md` does not describe how Trivy runs: unchanged)
- [x] Push, PR #102, CURRENT to PR_OPEN

## Next action
Read run 2's image-scan log: "Cache restored from key: trivy-39646793637" and the scan still green; add both runs to the PR description. Then STOP for the owner. After the merge: merge verification (the push run on `main` must be green); the owner pushes `ki-078-fixed` (403 here).

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
