# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-10
- **Phase:** 35, Client Authentication (mTLS for Kafka and PostgreSQL, per-service Kafka ACLs; KI-061)
- **Branch:** feature/phase-35-client-authentication (cut from `main` at `a371e15`)
- **Step:** IMPLEMENTING
- **Waiting for user:** NO

## Checklist (from the phase file; k8s only, compose unchanged)
- [ ] Client certificates `<service>-client-tls` for the 6 database clients (+ manifest test)
- [ ] PostgreSQL: `clientcert=verify-full`, `ssl_ca_file`; JDBC clients send certificate and key (+ IT)
- [ ] Kafka broker: `ssl.client.auth=required`, CA truststore, principal mapping, `StandardAuthorizer`, ACLs created by the pod
- [ ] Kafka clients: SSL bundle with the client certificate; every Kafka client in a service carries it; renewal
- [ ] ITs: with / without / other-CA certificate, CN != user, allowed / disallowed topic, renewal
- [ ] Smoke test (k8s blocks), certificate count 23
- [ ] Testing protocol, `docs/test-reports/phase-35.md`, README, decisions, security.md, RECENT.md, tracker 🔵
- [ ] PR

## Decisions so far
- pg_hba `map=` is refused with `scram-sha-256` (prototype: "only valid for ident, peer, gssapi, sspi, cert, and oauth"),
  so with password AND certificate the CN must BE the database user: CNs are ecomdemo, catalog, customer, inventory,
  notification, payment; Kafka principals are the same names.
- pgjdbc 42.7.13 reads an unencrypted PKCS#8 PEM key (`BEGIN PRIVATE KEY`) but assumes RSA unless `pemKeyAlgorithm` is set,
  a camelCase name an environment variable cannot express (Boot lowercases map keys): client certificates are RSA, PKCS#8.
- Boot 4.1 applies `spring.kafka.ssl.bundle` only through `KafkaConnectionDetails`: `KafkaProperties.build*Properties()`
  has no SSL; catalog's `StockChangedListenerConfig` used it and must copy Boot's consumer factory instead.

## Next action
Write the 6 client Certificates in `k8s/service-certificates.yaml` and `ClientAuthConfigTest` (fails before).

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
