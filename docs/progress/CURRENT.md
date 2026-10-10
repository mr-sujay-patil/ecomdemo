# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-10
- **Fix:** KI-058, PostgreSQL TLS in k8s (the 6 databases)
- **Branch:** fix/ki-058-postgres-tls (cut from `main` at `669a9ed`)
- **Step:** BRANCHED
- **Waiting for user:** NO. The k8s smoke test is run by the user locally (the cloud session's network policy blocks the Helm chart hosts and quay.io; same arrangement as KI-060). `ki-060-fixed` is for the user to push (the session's tag push got 403).

## Plan (k8s only; compose unchanged, as KI-057 did for Redis)
- Server: a cert-manager Certificate per database (`<db>-tls`, its Service names); Postgres started with `ssl=on`, the certificate files, and an `hba_file` that accepts only `hostssl` (scram) for TCP; a `cert-reload` sidecar that runs `pg_reload_conf()` when the certificate changes (Postgres re-reads its SSL files on reload).
- Clients: `SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SSLMODE=verify-full` and `..._SSLROOTCERT=/etc/ecomdemo-tls/ca.crt` in the 6 ConfigMaps (each pod already mounts the CA there); Flyway uses the same DataSource.

## Checklist
- [ ] Regression tests: manifest test (fails first) and a real Postgres TLS check (plain refused, verify-full with the CA accepted, a wrong host name refused)
- [ ] 6 Certificates, 6 StatefulSets (TLS, hba, sidecar), 6 ConfigMaps
- [ ] `scripts/smoke-test.sh` (k8s only): a plain-text client is refused by each database; certificate count
- [ ] Docs: KNOWN_ISSUES, README TLS, security.md, decisions [Fix KI-058]
- [ ] `./mvnw -B clean verify`; PR; CI green

## Next action
Write the manifest regression test, see it fail, then the server side.

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
