# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-056 phase A, services over HTTPS in k8s (server-side TLS; Redis/PostgreSQL/Kafka TLS are KI-057..059)
- **Branch:** fix/ki-056-services-https (cut from `main` at `0cea876`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-056 PR)

## Done
- User chose scope A only + no mTLS (2026-10-08). 8 cert-manager Certificates, SSL bundle per service, `trust-ca` init container + JAVA_TOOL_OPTIONS,
  HTTPS probes (the gateway's management port included), Traefik `ServersTransport` + `ecomdemo-ca-public` Secret, `https://` URLs in the ConfigMaps.
- Smoke: 6 new checks; `scripts/k8s-smoke.sh` 459 passed, 0 failed, 7 skipped. Forced certificate renewal: live pods served the new serial in ~60 s, 0 restarts.
- Docs: README TLS, security.md, KNOWN_ISSUES (KI-056 fixed, KI-057..059 new), `decisions.md` [Fix KI-056].

## Next action
STOP: wait for review. After `approved, merge it`: confirm CI green, merge --merge, verify in `main`, tag `ki-056-fixed`.
An existing cluster picks this up with `scripts/k8s-up.sh` (no recreate needed this time).
Remaining gaps: KI-016..024, 031..036, 048 (needs a Sonar server/token), 054, 055, 057, 058, 059.

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
