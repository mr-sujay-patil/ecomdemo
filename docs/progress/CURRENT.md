# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-05
- **Fix:** KI-062 (the burst's admitted requests come back 503: a cold page cache stampedes catalog-service, timeouts open the gateway's breaker). The owner: "continue", on the recommendation to take KI-062
- **Branch:** fix/ki-062-listing-stampede (cut from `main` at `e836bd3`)
- **Step:** PR_OPEN (#115), waiting on CI
- **Waiting for user:** no
- **History:** KI-083 (PR #114) merged as `e836bd3`, merge-verified 2026-11-05 (`./mvnw -B test` 0 failures, 29 Docker-only errors). Tags for the owner (403 here): `ki-083-fixed` `e836bd3`, `ki-082-fixed` `eeaa277`, `ki-032-fixed` `9ce4adf`, `ki-031-fixed` `d939733`, `ki-073-fixed` `b712df7`, `ki-076-fixed`/`ki-079-fixed` `f68f00b`, `ki-080-fixed` `f872e3d`, `ki-078-fixed` `d69b738`, `ki-077-fixed` `8d36b19`, `ki-074-fixed`/`ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`

## Checklist
- [x] Re-checked: CI bursts 1, 8, 114 x 503; `findPage` `@Cacheable` without `sync`; `DefaultRedisCacheWriter.get(.., Supplier, ..)` only locks with `enableLocking()` (a Redis lock key `productList~lock` the `catalog` ACL user may not touch); breaker window 10, min 5, 50 %, 10 s open
- [x] Red first: `LoggingCacheTest` red on `main` (20 concurrent misses ran the loader 20 times, expected 1); `CacheApiIT.concurrentColdReadsLoadThePageOnce` (CI, Docker)
- [x] Fix: per-key single flight in `LoggingCache.get(key, loader)` (a failed load not cached, a failed write still returns the value); `findPage` `@Cacheable(sync = true)`; catalog-service unit run 96 tests, 0 failures (5 Docker-only errors, CatalogSchemaTest); docs (KNOWN_ISSUES, decisions)
- [x] PR #115 with `run-smoke`
- [ ] CI green (CacheApiIT's concurrent case; the smoke's burst line, 503 count expected ~0); STOP for review

## Next action
Watch PR #115 CI; when green, STOP for the owner's review.

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
