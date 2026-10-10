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

## Phase 34: Product Images (tag: phase-34-complete, PR #59)
**What exists now:** `ProductResponse.imageUrl` (nullable, additive; also in search hits via `ProductSearchHit.product`)
is the gateway-relative path `/api/products/{id}/image`. The endpoint is public through the gateway, with
ETag/304, `Cache-Control: public, max-age=86400`, nosniff, CORP cross-origin, a CSP on SVG. Seed-only: 8 of
the 10 seeded products have an SVG, products 9 and 10 have none; products created via the API have none.
**Key code:** catalog-service `Product.imageFile` (V5 `image_file`, no setter), `dto.ProductResponse.imageUrl`,
`ProductImageService` (plain-name + extension allow-list, classpath `product-images/`, SHA-256 ETag, cached),
`ProductImage` record, `internal.ProductImageController` (`WebRequest.checkNotModified`).
**Config & infrastructure:** none new. Images: `catalog-service/src/main/resources/product-images/`, written
by `scripts/generate-product-images.py`. No gateway or security-rule change: `GET /api/products/**` was already public.
**Tests:** 724 (542 unit, 182 IT). New: `ProductResponseTest`, `ProductImageServiceTest`, `ProductImageFilesTest`
(type, 256 KiB cap, inert SVG, V5 names only shipped files), `ProductImageControllerTest`; image tests in
`ProductApiIT` and `EdgeSecurityIT`. Smoke section "Product images" (12 checks): compose cold 480/0/0, kept volumes
482/0/0, kind 436/0/7.
**Gotchas:** catalog-service is internal: its image path needs a token, anonymous access is the gateway's
(tests say so). A new image file needs a V-migration naming it AND the extension on the allow-list. `ProductResponse`
is cached in Redis (product 10 min, list 2 min): after deploying onto a warm Redis, entries written before
this phase read `imageUrl` as null until they expire. Self-heals; a cold stack (`down -v`) never sees it.
**Follow-ups (not done):** admin upload of images (storage, size and content validation, cleanup); thumbnails or
`srcset` variants if raster images arrive; a versioned URL so caching could be `immutable`.
