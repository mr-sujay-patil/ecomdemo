# Test report: Phase 35, Client Authentication

Branch `feature/phase-35-client-authentication`, 2026-10-10. Run in the cloud development container (Docker
available; no kind cluster can be created there, see "Not run").

## 1. Full regression

`./mvnw -B clean verify`: **BUILD SUCCESS**, **846 tests** (634 unit, 212 integration), 0 failures, 0 errors,
0 skipped. Baseline before the phase: 789. New: 57.

| Module / class | Tests | What it proves |
|---|---|---|
| `ClientAuthConfigTest` (ecomdemo-app) | 44 | Manifests: six client certificates (CN = the database user from each StatefulSet, `client auth`, RSA, PKCS#8, cluster CA); `hostssl ... scram-sha-256 clientcert=verify-full` is the only TCP rule; `ssl_ca_file` on all six databases; each client's `sslcert`/`sslkey` and mount; key Secret `defaultMode` 0440 and `fsGroup` = the Dockerfile's gid; Kafka bundle settings in the five Kafka clients; broker client auth, truststore, authorizer, super user; the principal mapping (CN=ANONYMOUS stays a DN); ACL table least-privilege rules and sample lines matched to the code; the `acls` container and its readiness. **Run against `main`'s manifests: 33 of 44 fail** (the other 11 read the new ACL file, which that checkout left in place). |
| `PostgresTlsIT` (ecomdemo-app, +4 → 8) | 8 | Both database images with the manifests' own start command and `pg_hba.conf`, the client side bound from app's ConfigMap: the right certificate is the one the server saw (`client_dn` = `/CN=<user>`); no certificate ("requires a valid client certificate"), another CA's, another user's (log: "CN mismatch"), a wrong password with the right certificate, a world-readable key ("has group or world access") and an EC key are refused; `PEMKEYALGORITHM` binds as `pemkeyalgorithm` (why RSA); a renewed client certificate is presented by the next connection. The KI-058 tests still pass with a certificate. |
| `KafkaClientAuthIT` (ecomdemo-app, new) | 5 | One broker as the StatefulSet runs it, with the `acls` container's own script: the broker's ACLs are the table (67 bindings), a second sync removes a hand-added ACL and adds nothing, a third changes nothing; app's certificate (through Spring Boot, as the pod configures it) writes `orders.created` with an idempotent producer, is refused `catalog.product-changed`, reads `payments.completed` in group `order-service`, is refused group `payment-service`; notification's certificate is refused `payments.completed` and reads `orders.placed` in `ecomdemo-notification-retry-0` (prefixed group ACL); no certificate and another CA's fail the handshake; CN=ANONYMOUS is refused; a renewed client certificate is used by the next connection. 82 s. |
| `KafkaTlsIT` (ecomdemo-app, rewritten on `KafkaTestBroker`) | 2 | KI-059's checks, now with a client certificate: SSL with CA and name works, plain text and a wrong host name refused, broker renewal reloaded. |
| `KafkaClientCertificateConfigTest` (outbox, new) | 3 | Producer and consumer factories carry the registry's current bundle before and after a reload; inactive without `spring.kafka.ssl.bundle`; an unknown bundle fails start-up. |
| `StockChangedListenerConfigTest` (catalog-service, +1) | 3 | The stock-changed consumer carries Boot's consumer factory settings (customizers, security protocol). **Fails on the old code** (it used `KafkaProperties.buildConsumerProperties()`, which has no SSL bundle in Boot 4). |

**Counter-check:** with `KafkaClientCertificateConfig` removed from the test fixture, `KafkaClientAuthIT.renewedCertificateIsUsed` times out (the old certificate is still presented), so the test depends on `CurrentSslBundle`.

## 2. Smoke test additions, validated against containers

`scripts/k8s-smoke.sh` could not run here. The new and changed k8s lines were cut out of `scripts/smoke-test.sh`
verbatim and run with a `kube` shim mapping `kube exec statefulset/<x> -c <c> --` to `docker exec`, against a broker
started with `k8s/data/kafka.yaml`'s environment and start command (alias `kafka`, the `acls` container's script run
in it) and a database started with `k8s/data/db.yaml`'s command and `postgres-hba` (alias `db`), with a client holding
an `ecomdemo` certificate connection open:

| Check | Result |
|---|---|
| a TLS client without a client certificate is refused by kafka:9092 | PASS |
| notification-service's client certificate is copied into the broker's pod | PASS (files removed after: 0 left) |
| notification-service's certificate reads orders.placed | PASS |
| but may not read payments.completed | PASS |
| KI-059: kafka:9092 is TLS, verifies as kafka / plain text gets no answer | PASS / PASS |
| db refuses a plain-text client | PASS |
| db: verify-full TLS as its Service name, then refused without a client certificate | PASS |
| db: its service's connections present /CN=ecomdemo | PASS |

## 3. Not run, and why

- ⚠️ **`scripts/k8s-up.sh` and `scripts/k8s-smoke.sh`** (the full flow on kind with cert-manager): this container
  cannot build a kind cluster (the Helm chart hosts and quay.io are blocked). The owner runs them locally:
  `scripts/k8s-up.sh` (the databases, Kafka and the six services roll out on their own: their pod templates
  change), then `scripts/k8s-smoke.sh`. Expect 23 certificates. That run is also the end-to-end check of the ACL
  table against every code path (saga, dead letters, retry topics, replay); a missing line shows up as
  `TopicAuthorizationException` / `GroupAuthorizationException` in a service log.
- The compose smoke test: compose is unchanged by this phase (all changes are k8s manifests, k8s-only smoke
  blocks, and code that is inactive without `spring.kafka.ssl.bundle`, which compose does not set).
- `performance-tests` compile: no API change.

## 4. Clean-up

All test and emulation containers removed; the background dockerd started for this session keeps running.
