# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-051, edge TLS at the Ingress (the hops behind it are KI-056)
- **Branch:** fix/ki-051-edge-tls (cut from `main` at `37cf69e`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-051 PR; also see "User action")

## Done
- User chose: edge only, cert-manager with a local CA, recreate the kind cluster (2026-10-08).
- kind port 30443 -> host 18443; Traefik websecure exposed (entrypoint listens on 18443 in the pod so its 301 points at the client's port);
  cert-manager v1.20.4 + `k8s/platform/ca.yaml`; `k8s/tls-certificate.yaml`; Ingress `tls`; `k8s-up.sh` installs it and exports `.local/ecomdemo-ca.crt`.
- Smoke: 6 new checks; `scripts/k8s-smoke.sh` 453 passed, 0 failed, 7 skipped (before: plain HTTP answered 200, no HTTPS port).
- README "TLS at the Ingress", security.md, KNOWN_ISSUES (KI-051 fixed, KI-056 new), `decisions.md` [Fix KI-051].

## User action / hand-over
- The kind cluster was RECREATED: databases emptied; the `ecomdemo-web` workload (not from this repo) deleted. Its manifests are saved in the
  job tmp dir (`ecomdemo-web-backup.yaml`) and its image `ecomdemo-web:k8s` is still on the host. Not restored (it is the frontend team's).
- The frontend team: k8s base URL is now `https://localhost:18443` and the CA (`.local/ecomdemo-ca.crt`) must be trusted; compose is unchanged.

## Next action
STOP: wait for review. After `approved, merge it`: confirm CI green, merge --merge, verify in `main`, tag `ki-051-fixed`.
Remaining gaps: KI-016..024, 031..036, 048 (needs a Sonar server/token), 054, 055, 056.

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
