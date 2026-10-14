# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-14
- **Fix:** KI-076 (`PostgresTlsIT.renewalIsReloaded` fails under a loaded full verify); approved 2026-10-14 ("KI-076 flaky Postgres test")
- **Branch:** fix/ki-076-postgres-renewal-it (cut from `main` at `e0a13eb`)
- **Step:** FIX (root cause found; proving it)
- **Waiting for user:** NO
- **History:** KI-074 + KI-075 (PR #99) merged as `e0a13eb` and verified; the owner pushes `ki-074-fixed` and `ki-075-fixed` (tag pushes are refused here, 403)

## Checklist
- [x] Root cause: a race, not a short window. The test copied the cert, then the key; the reloader (1 s) waking between them installs new cert + OLD key, Postgres refuses (`key values mismatch`, `SSL configuration was not reloaded`), `pg_reload_conf()` still says `t`, the script logs "reloaded" and never retries. Deterministic repro fails 2/2 (30 s, PKIX); under load one copy took 2.2 s (> the 1 s interval). Original failure (`full-verify-1.log`, 2026-10-11): catalog-db, 34.49 s, same PKIX
- [x] Fix (`b89dbfe`, test only): wait for the script's `+ last=` (`sh -x` trace), then key first, cert last; timeout and assertions unchanged. Adversarial (reloader acts between the copies) passes
- [ ] Prove it: fails (or measured slowness beyond the old window) before, passes after, 20 runs alone + several under load
- [x] Siblings: `KafkaTlsIT.renewalIsReloaded` shares it (repro: broker takes the mismatched pair, `Invalid CertificateVerify signature`, 60 s) → KI-077; Redis already copies the key first; the sidecars' false "reloaded" → KI-078
- [ ] `./mvnw -B clean verify` once
- [ ] Docs: KNOWN_ISSUES (Fixed (PR #NN), root cause), decisions [Fix KI-076]
- [ ] Push, PR, CURRENT to PR_OPEN

## Next action
Collect the repeat-run evidence in `~/.cache/ecomdemo-claude/ki076/{before-load,after-alone,after-load}/summary.txt` (loops running; don't edit `PostgresTlsIT.java` while they run), put the numbers in the KI-076 row and PR body, then the full `./mvnw -B clean verify` in the background, then push and open the PR (no `run-smoke`: test only).

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
