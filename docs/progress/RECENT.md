# Recent Phase Summaries (rolling window: last 2 phases)

> Newest first. When a third summary is added, move the oldest to `docs/progress/archive/phase-XX-summary.md`. Maximum ~30 lines per summary: facts only, no narrative.

<!-- TEMPLATE
## Phase XX: <Title> (tag: phase-XX-complete, PR #N)
**What exists now:** <1–3 lines describing the system after this phase>
**Key code:** <packages and classes that matter next>
**Config & infrastructure:** <profiles, env vars, ports, containers, and how to run>
**Tests:** <new test classes, smoke test additions>
**Gotchas:** <anything surprising the next phase must know>
**Follow-ups (not done, out of scope):** <suggestions deferred to later phases>
-->

## Phase 36: Redis Client Certificates (tag: phase-36-complete, PR #TBD)
**What exists now:** in k8s, Redis requires a client certificate from the cluster CA AND the password
(`--tls-auth-clients yes`, `requirepass` kept). Clients: app (CN ecomdemo), catalog-service (catalog) reuse their
Phase 35 `<service>-client-tls`; new `gateway-service-client-tls` (gateway), `assistant-service-client-tls`
(assistant), same kind (RSA PKCS#8, client auth), mounted `/etc/ecomdemo-client-tls` (0440, `fsGroup: 1001`).
`cache-tls` now lists `server auth` + `client auth`: the probe, `cert-reload` and smoke `redis-cli` present it.
**Key code:** `common` `com.ecomdemo.redis.RedisClientCertificateAutoConfiguration` (auto-configuration in
`META-INF/spring/...AutoConfiguration.imports`; active with `spring.data.redis.ssl.bundle` + Lettuce) and
`CurrentBundleManagers` (key/trust manager factories that ask the registry per call). `common` has the Redis
starter as `optional`.
**Config & infrastructure:** clients: `SPRING_DATA_REDIS_SSL_BUNDLE=redis`, `SPRING_SSL_BUNDLE_PEM_REDIS_{KEYSTORE_CERTIFICATE,
KEYSTORE_PRIVATEKEY,TRUSTSTORE_CERTIFICATE,RELOAD_ON_UPDATE}` (Redis trusts the CA through the bundle now, not the
JVM truststore). 25 certificates. Existing cluster: `k8s-up.sh` then `kubectl -n ecomdemo rollout restart deploy`
(app and catalog change only in ConfigMaps). Compose unchanged.
**Tests:** `RedisClientAuthConfigTest` (15), `RedisClientAuthIT` (8: cert+password, no cert, other CA, no
password, renewal, Boot alone keeps the old certificate, probe + `server auth`-only refused, sidecar reload),
`RedisClientCertificateAutoConfigurationTest` (5, common). Smoke (k8s): no-cert and foreign-CA refusals (with
Redis's log reason), each of the 4 client certificates PONG, 25 certificates.
**Gotchas:** Boot 4.1 hands Lettuce the bundle's KeyManagerFactory once (no reload); Lettuce builds an SSL
engine per connection, so per-call managers fix it; an open connection keeps its TLS session. Redis refuses
a `server auth`-only client certificate. A new Redis client needs a client certificate, the `redis` bundle
settings and the mount (`RedisClientAuthConfigTest` finds clients by `SPRING_DATA_REDIS_HOST`).
**Follow-ups (not done):** per-service Redis ACL users (KI-065; Redis 8.10 has `tls-auth-clients-user`);
the smoke test's host `redis-cli` path on k8s (KI-064).

## Phase 35: Client Authentication (tag: phase-35-complete, PR #89)
**What exists now:** in k8s, PostgreSQL and Kafka authenticate clients by certificate; Kafka authorizes per service.
Six client certs `<service>-client-tls` (cert-manager, `client auth`, RSA PKCS#8, CN = DB user: ecomdemo, catalog,
customer, inventory, notification, payment), mounted `/etc/ecomdemo-client-tls` (0440, pod `fsGroup: 1001`).
PG: `hostssl all all all scram-sha-256 clientcert=verify-full`, `-c ssl_ca_file`. Kafka: `ssl.client.auth=required`,
CA truststore, `RULE:^CN=([a-z0-9-]+)$/$1/,DEFAULT`, `StandardAuthorizer`, `super.users=User:ANONYMOUS` (loopback only).
**Key code:** `k8s/data/kafka-acls.yaml` (ACL table + `sync.sh`: add missing, remove extra) run by the broker pod's
`acls` container (Ready after sync). outbox `internal.KafkaClientCertificateConfig` + `CurrentSslBundle` (factories
use the registry's current bundle). catalog `StockChangedListenerConfig` copies Boot's consumer factory.
**Config & infrastructure:** clients: `SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SSLCERT/_SSLKEY`; Kafka:
`SPRING_KAFKA_SSL_BUNDLE=kafka`, `SPRING_SSL_BUNDLE_PEM_KAFKA_{KEYSTORE_CERTIFICATE,KEYSTORE_PRIVATEKEY,TRUSTSTORE_CERTIFICATE,RELOAD_ON_UPDATE}`
(KI-059's `SPRING_KAFKA_PROPERTIES_SSL_TRUSTSTORE_*` removed). 23 certificates. Compose unchanged.
**Tests:** `ClientAuthConfigTest` (44), `PostgresTlsIT` (+4), `KafkaClientAuthIT` (5, one broker, the real ACL
sync), `KafkaTestBroker` fixture (shared with `KafkaTlsIT`), `KafkaClientCertificateConfigTest`,
`StockChangedListenerConfigTest` (+1). Smoke (k8s): no-cert refusals, client_dn per DB, notification vs payments.completed.
**Gotchas:** a new topic, consumer group, `NewTopic` or dead-letter path needs a line in `kafka-acls.yaml` (else
TopicAuthorization/GroupAuthorization in k8s only; compose has no ACLs). In Boot 4, `KafkaProperties.build*Properties()`
has NO SSL bundle: build Kafka clients from Boot's factories. pgjdbc: PEM keys only PKCS#8, RSA unless `pemKeyAlgorithm`
(not settable via env), key file must not be world-readable. `map=` is not allowed with scram in pg_hba.
**Follow-ups (not done):** Redis client certificates; KafkaAdmin keeps the start-up certificate; ACLs in compose.
