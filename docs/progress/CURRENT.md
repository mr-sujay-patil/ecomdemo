# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-004, no timeouts or circuit breaker on the app's inventory calls or on the gateway's `/api/products` route
- **Branch:** fix/ki-004-timeouts-circuit-breaker (cut from `main` at `e7b0f98`)
- **Step:** IMPLEMENTING
- **PR:** none yet
- **Waiting for user:** no

## Merge verification of KI-003 (done 2026-10-08)
PR #66 merged as `e7b0f98`; branch is an ancestor of `main`, diff empty, `./mvnw clean verify` exit 0, CI on `main` green
incl. scans and publish; tag `ki-003-fixed` pushed. Cold compose smoke skipped (config-only change, run on the branch tree).

## Plan (KI-004 has only a table row; scope read from `security.md` API4/API10)
1. [x] `common`: inventory client connect/read timeouts (`InventoryProperties`, `InventoryClientConfig`), `InventoryClientTimeoutTest`.
2. [ ] app: `ResilientInventory` (bulkhead + breaker, retry on reads only) wrapped in place like `ResilientCatalog`; `resilience4j.*.instances.inventory.*`; test.
3. [ ] gateway: split `/api/products` into a fast GET route (response timeout + CircuitBreaker, 503 fallback with Retry-After)
   and the rest (long timeout: AI generation, search); test.
4. [ ] docs: `security.md` API4/API10, KNOWN_ISSUES row, `decisions.md`; smoke-test additions if cheap.
5. [ ] `./mvnw clean verify`, smoke on compose (`docker ps` first), PR.

## Next action
Continue at step 2. Not in scope (suggest only): catalog-service's own inventory calls get timeouts via the shared
client but no breaker; the saga clients already have their own timeouts (Phase 32).
`stash@{0}` (KI-045 noise) is obsolete; the user's to drop.

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
