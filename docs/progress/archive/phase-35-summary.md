# Phase 35 summary (moved from RECENT.md when Phase 37 was added)

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
