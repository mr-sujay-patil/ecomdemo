# Test report: Phase 36, Redis Client Certificates

Branch `feature/phase-36-redis-client-certificates`, 2026-10-10. Run in the cloud development container (Docker
available; no kind cluster can be created there, see "Not run").

## 1. Full regression

`./mvnw -B clean verify`: **BUILD SUCCESS**, **874 tests** (654 unit, 220 integration), 0 failures, 0 errors,
0 skipped. Baseline before the phase: 846. New: 28.

| Module / class | Tests | What it proves |
|---|---|---|
| `RedisClientAuthConfigTest` (ecomdemo-app, new) | 15 | Manifests: Redis has `--tls-auth-clients yes`, the CA and still `--requirepass`; every `redis-cli` in the Redis pod (probe, `cert-reload`) presents the pod's certificate; `cache-tls` lists `server auth` and `client auth`; the Redis clients are exactly the four ConfigMaps that set `SPRING_DATA_REDIS_HOST`; gateway and assistant client certificates (CN, `client auth` only, RSA PKCS#8, cluster CA); each client's `redis` SSL bundle settings and its 0440 mount with `fsGroup` 1001; the smoke test's k8s `REDIS_TLS` presents the certificate and counts 25 certificates. **12 of 15 fail on `main`** (the three that pass there: the client list, and app's and catalog's existing mounts). |
| `RedisClientAuthIT` (ecomdemo-app, new) | 8 | `redis:8-alpine` with `k8s/data/cache.yaml`'s own arguments, the client built from app's ConfigMap as Boot binds the environment: certificate + password gives PONG; no certificate refused ("peer did not return a certificate" in Redis's log); another CA's certificate refused ("certificate verify failed"); certificate without password gets NOAUTH; a renewed client certificate is used by the next connection; with Boot's own configuration only, three new connections after the bundle reloaded are still refused; the readiness probe gets PONG, and the same probe with a `server auth`-only certificate does not; the `cert-reload` sidecar's own script (30 s check shortened to 1 s) makes Redis serve a renewed certificate. |
| `RedisClientCertificateAutoConfigurationTest` (common, new) | 5 | Inactive without `spring.data.redis.ssl.bundle`; inactive without Lettuce on the classpath (the services without Redis); every SSL context Lettuce builds asks the registry; an unknown bundle fails the start by name; the managers handed out are the registry's current bundle's after an update. |

`ModularityTest` passes with the new `com.ecomdemo.redis` module (no dependencies); its documenter regenerated
`docs/modules/` (the new `module-redis.*`, and component order in six diagrams).

**Counter-check:** `RedisClientAuthIT` run against `main`'s `k8s/data/cache.yaml` (`--tls-auth-clients no`): 5 of 8
fail (no certificate and another CA are accepted, so the refusals and both renewal tests fail, and the
`server auth`-only probe gets PONG). The Boot-alone test is the counter-check of the renewal code: it shows that
without `RedisClientCertificateAutoConfiguration` the renewed certificate is not used.

## 2. Smoke test additions, validated against a container

`scripts/k8s-smoke.sh` could not run here. The k8s `REDIS_TLS`, the Caching section's Redis checks and the new
Phase 36 block were cut out of `scripts/smoke-test.sh` verbatim and run with a `kube` shim (`kube exec` → `docker
exec`, `kube logs` → `docker logs --since 60s`, `kube get secret` → JSON of a test certificate) against a Redis
container started with the manifest's arguments, the cluster-CA files mounted at `/etc/ecomdemo-tls` and
`REDISCLI_AUTH` set:

| Check | Phase 36 Redis | KI-057 Redis (`--tls-auth-clients no`) |
|---|---|---|
| Redis refuses a client that has no password (now with the pod's certificate) | PASS | PASS |
| and answers one that has it | PASS | PASS |
| Redis serves TLS only: a plain-text client does not get PONG | PASS | PASS |
| Redis refuses a TLS client without a client certificate | PASS | FAIL (as it should) |
| Redis refuses a client certificate from another CA | PASS | FAIL (as it should) |
| app's / catalog-service's / gateway-service's / assistant-service's client certificate gets PONG | PASS ×4 | PASS ×4 |

The certificate count (25) was not run: it needs cert-manager.

## 3. Not run, and why

- ⚠️ **`scripts/k8s-up.sh` and `scripts/k8s-smoke.sh`** (kind with cert-manager): this container cannot build a kind
  cluster (the Helm chart hosts and quay.io are blocked). The owner runs them locally: `scripts/k8s-up.sh`, then
  `kubectl -n ecomdemo rollout restart deploy` (app and catalog-service change only in their ConfigMaps, which
  restarts nothing), then `scripts/k8s-smoke.sh`. Expect 25 certificates. That run is also the only end-to-end
  check of cert-manager's real `cache-tls` (with both usages) and of the services on the cluster.
- ⚠️ **A renewal on the cluster** (`cmctl renew` of a client certificate, then a new Redis connection): covered by
  `RedisClientAuthIT` on files, not tried with cert-manager's Secret updates.
- The compose smoke test: compose is unchanged (all changes are k8s manifests, k8s-only smoke lines, and code that
  is inactive without `spring.data.redis.ssl.bundle`, which compose does not set).
- `performance-tests` compile: no API change.

## 4. Clean-up

All test and emulation containers removed; the background dockerd started for this session keeps running.
