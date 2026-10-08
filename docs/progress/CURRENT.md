# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-004, no timeouts or circuit breaker on the app's inventory calls or on the gateway's `/api/products` route
- **Branch:** fix/ki-004-timeouts-circuit-breaker (cut from `main` at `e7b0f98`)
- **Step:** PR_OPEN
- **PR:** none yet
- **Waiting for user:** YES (review of the KI-004 PR)

## Merge verification of KI-003 (done 2026-10-08)
PR #66 merged as `e7b0f98`; branch is an ancestor of `main`, diff empty, `./mvnw clean verify` exit 0, CI on `main` green
incl. scans and publish; tag `ki-003-fixed` pushed. Cold compose smoke skipped (config-only change, run on the branch tree).

## Done (KI-004 has only a table row; scope read from `security.md` API4/API10)
- `common`: inventory client connect 250 ms / read 500 ms (`InventoryProperties`, `InventoryClientConfig`); `InventoryClientTimeoutTest` (seen failing first).
- app: `ResilientInventory` + `InventoryResilienceConfig` (bulkhead 50, breaker, retry on reads only; `release` never throws);
  `resilience4j.*.instances.inventory.*`; `ResilientInventoryTest` (written after the code, not seen failing first).
- gateway: `/api/products` split into `catalog-slow` (90 s), `catalog-read` (GET, 2 s, CircuitBreaker, 502/503/504 -> `/fallback/catalog` 503 + Retry-After), `catalog` (30 s);
  `CatalogRouteResilienceIT` against a slow stub (seen failing against the old single route).
- Docs: `security.md`, `decisions.md`, KNOWN_ISSUES row.
- `./mvnw clean verify` exit 0 (2026-10-08). **Not run: the live compose smoke** (the frontend team's stack came up on the same
  names/ports while I started mine; I did not touch it). Kubernetes untouched.

## Next action
STOP: wait for the user's review of the KI-004 PR. After `approved, merge it`: wait for the required check, merge with
`--merge`, verify (tip in `main`, CI on `main` incl. scans and publish), tag `ki-004-fixed`. Replace `PR #TBD` in KNOWN_ISSUES
with the real number on this branch first. A cold compose smoke on `main` is owed if no foreign stack is up (`docker ps` first).
Suggested, not built: breaker on catalog-service's own inventory calls; bulkhead size 50 is a judgement, measure it.
Still open: KI-005..011. `stash@{0}` (KI-045 noise) is obsolete; the user's to drop.

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
