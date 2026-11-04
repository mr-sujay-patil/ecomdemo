# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-04
- **Fix:** KI-032 (the catalogue cache's hit rate is not observable: no `cache_gets_total` at all). Re-checked against the code; the owner said "continue on next" after KI-031
- **Branch:** fix/ki-032-cache-metrics (cut from `main` at `d939733`)
- **Step:** PR_OPEN (#112), waiting on CI
- **Waiting for user:** no
- **History:** KI-031 (PR #110) merged as `d939733`, merge-verified 2026-11-04 (tip `16438cd` in `main`; `./mvnw -B test` on `main`: 0 failures, the 29 Docker-only errors in 4 classes). Tags for the owner (403 here): `ki-031-fixed` `d939733`, `ki-073-fixed` `b712df7`, `ki-076-fixed`/`ki-079-fixed` `f68f00b`, `ki-080-fixed` `f872e3d`, `ki-078-fixed` `d69b738`, `ki-077-fixed` `8d36b19`, `ki-074-fixed`/`ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`. Web: the approved chore for KI-030/031's stale rows waits for web PR #61 (one PR at a time)

## Checklist
- [x] Re-checked: Boot binds `RedisCacheMetrics` only to a `RedisCache`; `LoggingCache` hides it; the writer collects no statistics
- [x] Red first: `CacheMetricsTest` (4 of 4 red on `main`: no `cache.gets` meter for either cache)
- [x] Fix: `collectStatistics()`; `CacheMeterBinderProvider<LoggingCache>` (named class) binding `RedisCacheMetrics` to `LoggingCache.getDelegate()`; dashboard panel 12 "Catalogue cache hit ratio" (`service="catalog-service"`); DashboardMetricsTest (`cache_gets_total` Boot-provided, panel pinned); smoke check (both caches' series, reads counted); docs (KNOWN_ISSUES, decisions, README caching section)
- [x] Full unit run: 0 failures (29 Docker-only errors, as on `main`); fix `53daaa1`; PR #112 with `run-smoke`
- [ ] CI green, then STOP for review

## Next action
Watch PR #112 CI; fix anything red; when green, STOP for the owner's review.

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
