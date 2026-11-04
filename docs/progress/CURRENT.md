# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-04
- **Fix:** KI-073 (ecomdemo-app keeps a Redis client it does not use: `spring-boot-starter-data-redis` + `-cache`, an ACL user `ecomdemo`, the `redis` SSL bundle, compose and k8s settings, all for Boot's health indicator). Approved 2026-11-04 ("continue on next" after KI-076/079)
- **Branch:** fix/ki-073-app-without-redis (cut from `main` at `f68f00b`)
- **Step:** IMPLEMENTING
- **Waiting for user:** no
- **History:** KI-076 + KI-079 (PR #108) merged as `f68f00b`, merge-verified 2026-11-04 (branch tip `a800a59` in `main`; manifests and tests only, so the `main` run is not waited on). The owner's k8s smoke for #108 is not confirmed. Tags for the owner (403 here): `ki-076-fixed`, `ki-079-fixed` `f68f00b`, `ki-080-fixed` `f872e3d`, `ki-078-fixed` `d69b738`, `ki-077-fixed` `8d36b19`, `ki-074-fixed`/`ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`

## Checklist
- [x] KI-073 In progress; inventory (2026-11-04): ecomdemo-app pom (cache + data-redis), application-dev Redis settings, IntegrationTest's Redis container and application-it `spring.cache.type=redis`, compose `app` (depends_on cache, SPRING_DATA_REDIS_*), k8s/services/app.yaml (SPRING_DATA_REDIS_*, the `redis` SSL bundle, init wait on cache:6379), k8s/data/cache-acl.yaml (user ecomdemo), RedisAcl/RedisAclConfigTest/RedisAclIT/RedisClientAuthIT/RedisClientAuthConfigTest (app as a Redis client), scripts/smoke-test.sh (app as a Redis client). The client certificate (CN ecomdemo) stays: PostgreSQL and Kafka use it
- [x] Red first (`d32dc43`): `RedisAclConfigTest.appIsNoRedisClient`, `oneUserPerClient`, `smokeTestChecksTheUsers`, `RedisClientAuthConfigTest.everyRedisClientIsListed` red (4 of 31); `RedisAclIT.appIsNoUser`, `RedisClientAuthIT` sample client now catalog-service
- [x] Fix across the inventory: pom (no cache starter, data-redis test-scoped), application-dev, `it`/`test` profiles exclude `DataRedisAutoConfiguration` (no Redis in the test context; no `spring.cache.type`), IntegrationTest without RedisContainerConfig, compose, k8s app ConfigMap and init wait, cache-acl (no `ecomdemo`), smoke test (app's certificate: NOAUTH), `RedisAclIT` (issues the `ecomdemo` certificate itself; ACL reload case uses `assistant`); docs (KNOWN_ISSUES, decisions, security, README)
- [ ] Tests (unit locally; ITs, image scan and compose smoke in CI with `run-smoke`), docs, PR; the owner runs the k8s smoke

## Next action
Config tests green locally; finish the module unit run (expect 0 failures plus the 29 Docker-only errors), commit, push, open the PR with label `run-smoke`, wait for CI, then STOP for review.

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
