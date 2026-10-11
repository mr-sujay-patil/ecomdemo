# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-11
- **Fix:** KI-074 (catalog clears its cache with KEYS) and KI-075 (async evictions never reach `CacheConfig`'s error handler); grouped per workflow rule 11 (same code: catalog's `CacheConfig` cache writer); approved 2026-10-11 ("KI-074 + KI-075")
- **Branch:** fix/ki-074-075-catalog-cache-scan (cut from `main` at `4767124`)
- **Step:** BRANCHED
- **Waiting for user:** no
- **History:** Phase 37 (PR #98) merged as `4767124`; the owner pushes `phase-37-complete` (tag pushes are refused here, 403)

## Checklist
- [ ] Regression tests (red first): `CatalogCacheAclIT` proves KEYS is never sent (INFO commandstats) and the clear still empties the keys under the `catalog` user with no `+keys`; a refused delete (user without `+del`/`+unlink`) reaches `CacheConfig`'s error handler (logged ERROR) and `ProductCacheEvictor`'s warning
- [ ] Fix KI-074: `BatchStrategies.scan(n)` in `CacheConfig`; ACL: `-keys`, `+scan`
- [ ] Fix KI-075: immediate (synchronous) cache writes in `CacheConfig`; ACL follows the commands sent (DEL, not UNLINK)
- [ ] `RedisAclConfigTest`, `RedisAclIT` follow the catalog line; smoke test: catalog refused KEYS
- [ ] `./mvnw -B clean verify`
- [ ] Docs: KNOWN_ISSUES (fixed), decisions [Fix KI-074, KI-075], comments in cache-acl.yaml, security.md/README where they name the commands
- [ ] Push, PR (label `run-smoke`), CURRENT to PR_OPEN

## Next action
Write the regression tests first and show them red on this tree (see the checklist), then implement.

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
