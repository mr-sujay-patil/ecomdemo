# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-10
- **Fix:** KI-060, the edge certificate does not name `shop.localhost` (unblocks web KI-034)
- **Branch:** fix/ki-060-shop-host-certificate (cut from `main` at `69915f2`)
- **Step:** BRANCHED
- **Waiting for user:** NO

## Checklist (from the issue's scope)
- [ ] Regression test: the edge Certificate names `shop.localhost`, and every Ingress `tls` host is in it (fails first)
- [ ] `k8s/tls-certificate.yaml`: add `shop.localhost`; `k8s/ingress.yaml`: add it to `tls.hosts`
- [ ] `scripts/k8s-smoke.sh`: a TLS check for `https://shop.localhost:18443` against the local CA
- [ ] Docs: KNOWN_ISSUES (fixed), README TLS section, `decisions.md` [KI-060] where they name the certificate's hosts
- [ ] PR. **The k8s smoke test is run by the user locally** (the cloud session's network policy blocks the Helm chart hosts and quay.io; the user chose this on 2026-10-10)

## Next action
Write the regression test (`ecomdemo-app/src/test/java/com/ecomdemo/EdgeCertificateConfigTest.java`, like `KafkaStorageConfigTest`), see it fail, then change the two manifests.

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
