# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-14
- **Fix:** KI-076 (`PostgresTlsIT.renewalIsReloaded` fails under a loaded full verify); approved 2026-10-14 ("KI-076 flaky Postgres test")
- **Branch:** fix/ki-076-postgres-renewal-it (cut from `main` at `e0a13eb`)
- **Step:** FIX (root cause found; proving it)
- **Waiting for user:** NO
- **History:** KI-074 + KI-075 (PR #99) merged as `e0a13eb` and verified; the owner pushes `ki-074-fixed` and `ki-075-fixed` (tag pushes are refused here, 403)

## Checklist
- [ ] Root cause: read the test, the `cert-reload` sidecar script it runs, the detection and its timeouts; reproduce (alone, repeated, and under load) and record the exact failure
- [ ] Fix the cause (test or sidecar), assertions intact
- [ ] Prove it: fails (or measured slowness beyond the old window) before, passes after, 20 runs alone + several under load
- [ ] Sibling renewal ITs (`KafkaTlsIT`, Redis) checked for the same pattern; new KI if they share it
- [ ] `./mvnw -B clean verify` once
- [ ] Docs: KNOWN_ISSUES (Fixed (PR #NN), root cause), decisions [Fix KI-076]
- [ ] Push, PR, CURRENT to PR_OPEN

## Next action
Root-cause KI-076: read `ecomdemo-app/src/test/java/com/ecomdemo/PostgresTlsIT.java` and the `cert-reload` script it extracts from the k8s manifest; list every timing assumption; reproduce.

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
