# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-10
- **Fix:** KI-059, Kafka TLS in k8s (the last TLS gap of KI-056)
- **Branch:** fix/ki-059-kafka-tls (cut from `main` at `553a02d`)
- **Step:** BRANCHED
- **Waiting for user:** NO. The k8s smoke test is run by the user locally (as for KI-060/058). Tags `ki-060-fixed` and `ki-058-fixed` are for the user to push (the session's tag pushes get 403).

## Plan (k8s only; compose unchanged)
- Broker: certificate `kafka-tls` (PKCS#8, which Kafka's PEM keystore needs); `INTERNAL://:9092` becomes SSL; a `LOCAL` plaintext listener on 127.0.0.1:9094 (inter-broker traffic, the in-pod CLI, the reload) and `CONTROLLER` on 127.0.0.1:9093, so nothing plain is reachable from the network. The start command writes key+chain to `keystore.pem` in an emptyDir.
- `cert-reload` sidecar: on renewal rewrites `keystore.pem` and runs `kafka-configs --alter ... listener.name.internal.ssl.keystore.location` (Kafka reloads the keystore). Prototype: new serial served, 0 restarts.
- Clients (5: app, catalog, inventory, notification, payment; `common`/`outbox` are libraries): `SPRING_KAFKA_SECURITY_PROTOCOL=SSL`, `SPRING_KAFKA_PROPERTIES_SSL_TRUSTSTORE_TYPE=PEM`, `..._LOCATION=/etc/ecomdemo-tls/ca.crt`; Kafka's hostname check (`https`) stays on.
- Smoke (k8s): in-pod CLI moves to localhost:9094; checks: plaintext to 9092 refused, TLS verified as `kafka`, 17 certificates.

## Next action
Regression tests (manifest test, then a Testcontainers IT like `PostgresTlsIT`), see them fail, then the manifests.

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
