# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-11
- **Phase:** 37: Redis ACL Users per Service (`docs/phases/phase-37-redis-acl-users.md`), closes KI-065; approved 2026-10-11 ("KI-065 phase")
- **Branch:** feature/phase-37-redis-acl-users (cut from `main` at `a71a161`)
- **Step:** IMPLEMENTING
- **Waiting for user:** NO
- **History:** Phase 36 (PR #91) merged and merge-verified earlier; the owner pushes `phase-36-complete` (tag pushes are refused here, 403). Fixes KI-064/066/067/068/069/071 merged since (PRs #92-#97)

## Checklist
- [x] Design: every Redis use per service from the code; the ACL per user; `default` off; identity from the CN (`tls-auth-clients-user CN`); the password decision; ops user; where the ACL lives. `[Phase 37]` decisions
- [x] Tests first (red, `55cf65a`): `RedisAclConfigTest` (manifests), `RedisAclIT` (real image, TLS, the manifest's ACL), gateway `RateLimitAclIT` (real limiter under its ACL user)
- [x] Implement: `k8s/data/cache-acl.yaml` (ConfigMap), `cache.yaml` (`--aclfile`, `--tls-auth-clients-user CN`, no `requirepass`, ACL reload in the sidecar), `cache-tls` CN, `k8s-up.sh` (no Redis password; removes the old key), Phase 36 tests updated to the new identity model
- [x] Smoke test (k8s): per-client own key OK / other key NOPERM / FLUSHALL refused / unknown CN NOAUTH; validated against a container with a `kube` shim
- [ ] `./mvnw -B clean verify` (full, background); `scripts/test-smoke-*.sh`
- [~] Docs: decisions, KNOWN_ISSUES (KI-065 fixed; new defects as rows), security.md, README, test report `docs/test-reports/phase-37.md`, RECENT (Phase 37 summary; Phase 35 to archive), tracker 🔵
- [ ] Push, PR (label `run-smoke`), CURRENT to PR_OPEN

## Next action
Docs: `[Phase 37]` decisions, KNOWN_ISSUES (KI-065 fixed; new KIs: app keeps an unused Redis connection, catalog clears with KEYS), security.md, README, test report, RECENT; then full `./mvnw -B clean verify` in the background.

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
