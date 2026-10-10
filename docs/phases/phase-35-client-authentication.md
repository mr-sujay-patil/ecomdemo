# Phase 35: Client Authentication

| | |
|---|---|
| **Stage** | Stage 9: Production Hardening |
| **Technology** | Mutual TLS (client certificates) for Kafka and PostgreSQL, Kafka ACLs (KRaft `StandardAuthorizer`) |
| **Branch** | `feature/phase-35-client-authentication` |
| **PR title** | `Phase 35: Client Authentication` |
| **Requires** | `ki-059` merged on `main` (Kafka is TLS-only in k8s; KI-058 and KI-057 before it) |
| **Completion tag** | `phase-35-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for Git. Approved by the user on 2026-10-10: **mTLS for Kafka and PostgreSQL, with per-service Kafka ACLs** (KI-061).

**Technology:** Client authentication by certificate, and authorization by identity, on the two data stores every service talks to

**Goal:** In Kubernetes, PostgreSQL and Kafka know WHO is connecting, not just that the line is encrypted. A database accepts a login only with the right password AND a certificate from the cluster CA naming that database user. Kafka accepts only clients with a certificate from the cluster CA, and lets each service touch only the topics and consumer groups its code uses. Compose stays as it is.

**What you'll implement**
- **Client certificates:** one cert-manager certificate per client (`<service>-client-tls`, from the `ecomdemo-ca` ClusterIssuer, `client auth` usage only, PKCS#8 key) for the six database clients: app, catalog-service, customer-service, inventory-service, notification-service, payment-service. The common name is the identity both stores read. Decide and record why separate certificates and not a `client auth` usage added to the `<service>-tls` server certificates, and which key algorithm and encoding the drivers accept (prove it in a test).
- **PostgreSQL:** `postgres-hba` becomes `hostssl all all all scram-sha-256 clientcert=verify-full` (password AND certificate; the certificate's CN must be the database user). Each database verifies clients against the cluster CA (`ssl_ca_file`). The JDBC clients send their certificate and key through Hikari's driver properties, set in each ConfigMap.
- **Kafka:** the `INTERNAL` listener requires a client certificate (`ssl.client.auth=required`) verified against the cluster CA; `ssl.principal.mapping.rules` turn the certificate's DN into a plain principal; the KRaft `StandardAuthorizer` is on with no access unless an ACL allows it. ACLs per service derived from the code: what each produces (outbox routes, direct sends, dead letters, replays), consumes (topics and consumer groups, retry-topic groups included), and creates (`NewTopic` beans through `KafkaAdmin`). The ACLs are created by the broker's pod itself, idempotently, through the loopback listener (no manual step). Decide and explain how the loopback listener and in-pod tools are authorized.
- **Kafka clients:** present their certificate through a Spring Boot SSL bundle; every Kafka client in the service (Boot's factories, the outbox relay, the dead-letter recoverer, the dead-letter reader, catalog's own stock-changed consumer) must carry it.
- **Renewal:** client certificates renew every 60 days. State and test what happens for each client on renewal; no silent expiry trap.
- **Tests:** a fast manifest test that fails before the change; Testcontainers ITs on the real images with the manifests' own configuration: with a certificate → OK; without → refused; a certificate from another CA → refused; PostgreSQL: CN ≠ user → refused; Kafka: an allowed topic works, a topic outside the service's ACLs → authorization error.
- **Docs:** README, `docs/decisions.md`, `docs/security.md`, KI-061.

**Concepts to understand**
- Server authentication vs client authentication: what TLS proved before this phase and what it proves now
- Authentication (who are you) vs authorization (what may you do): `pg_hba.conf` vs Kafka ACLs
- How a certificate becomes an identity: PostgreSQL's CN-to-user rule, Kafka's principal mapping rules
- Least privilege for event streams: producer, consumer, group and create rights, and why a dead-letter replay needs write access to another service's topic
- Certificate renewal: which clients read the file per connection and which load it once

**Done when**
- Each database refuses a client with no certificate, with a certificate from another CA, and with a certificate whose CN is not the user, and accepts the right one; Kafka refuses a client with no certificate or another CA's, lets a service use its own topics and refuses one outside its ACLs; a renewed client certificate is used by new connections without a restart. Each is proven by an automated test, and the k8s smoke test checks the refusals on the cluster.

## Smoke test additions (`scripts/smoke-test.sh`, k8s only)

- Each database: a TLS client without a certificate is refused ("requires a valid client certificate"), and the service's own pool is connected with its certificate (`pg_stat_ssl.client_dn`).
- Kafka: a TLS client without a certificate is refused; notification-service's certificate cannot read `payments.completed` (no ACL) but may describe `orders.placed` (its own topic).
- cert-manager: 23 certificates Ready (17 + 6 client certificates).

## Your manual steps (user)

Run `scripts/k8s-up.sh` (the databases, Kafka and the six clients roll out by themselves: their pod templates change), then `scripts/k8s-smoke.sh`. The session that builds this phase cannot create a kind cluster, so the k8s smoke test is yours to run.
