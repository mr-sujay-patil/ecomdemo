# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-10
- **Phase:** 36, Redis Client Certificates (`docs/phases/phase-36-redis-client-certificates.md`), KI-063
- **Branch:** feature/phase-36-redis-client-certificates (cut from `main` at `b2aa553`)
- **Step:** BRANCHED
- **Waiting for user:** NO. Approved by the user on 2026-10-10. Previous: Phase 35 merged (`bf0c109`, PR #89; its last commit `d21d639` is a parent of the merge), KI-055 merged (`b2aa553`, PR #90). Tags `phase-35-complete`, `ki-055-fixed`, `ki-059-fixed` are the owner's to push (403 here).

## Checklist
- [ ] Manifest test (fails first): Redis requires client certificates, every Redis client presents one through the `redis` SSL bundle, in-pod tools present one
- [ ] Redis: `--tls-auth-clients yes`; probe, `cert-reload` sidecar use a client certificate
- [ ] Client certificates for gateway-service and assistant-service; app and catalog reuse theirs
- [ ] Clients: `SPRING_DATA_REDIS_SSL_BUNDLE=redis` + `SPRING_SSL_BUNDLE_PEM_REDIS_*`; renewal (current-bundle key manager in `common`)
- [ ] `RedisClientAuthIT`: cert+password OK; no cert, other CA, no password refused; renewal used by next connection; probe and sidecar work
- [ ] Smoke test (k8s): no-cert and foreign-CA refusals, each service certificate PONG, 25 certificates
- [ ] Per-service ACL users: candidate KI row (not built)
- [ ] Docs: README, decisions, security, KI-063, test report, RECENT rotation, tracker 🔵
- [ ] `./mvnw -B clean verify` (baseline 846 tests); PR

## Next action
Write the manifest test `ecomdemo-app/src/test/java/com/ecomdemo/RedisClientAuthConfigTest.java` (it must fail on `main`), then change `k8s/data/cache.yaml`.

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
