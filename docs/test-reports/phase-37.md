# Test report: Phase 37, Redis ACL Users

Branch `feature/phase-37-redis-acl-users`, 2026-10-11. Run in the cloud development container (Docker available; no
kind cluster can be created there, see "Not run").

## 1. Full regression

`./mvnw -B clean verify`: **BUILD SUCCESS**, **929 tests** (686 unit, 243 integration), 0 failures, 0 errors,
0 skipped (the second run; see the flake below). New in this phase: 35 (19 unit, 16 integration).

| Module / class | Tests | What it proves |
|---|---|---|
| `RedisAclConfigTest` (ecomdemo-app, new) | 19 | Redis has `--tls-auth-clients-user CN` and `--aclfile`; no `requirepass`, no password or `REDISCLI_AUTH` in the Redis pod; the `cache-acl` ConfigMap is mounted read-only in Redis and the sidecar, and listed in kustomize before `cache.yaml`; the sidecar runs `ACL LOAD`; the users are exactly `default`, `cache-admin` and the four client certificates' CNs; `default` is off with no rights; `cache-admin` is `cache-tls`'s CN; no user has a password or `nopass`; each service user is enabled, names commands one by one (no categories, no channels, no admin or flush command; SELECT only for the gateway, KEYS only for catalog); every key prefix in the code (read from `CacheNames` and the assistant's `PREFIX` constants) is its own service's and no other's; no client sends a password or user name; `k8s-up.sh` removes the old key; the smoke test has the Phase 37 checks. **7 of 19 failed before the manifests changed** (`55cf65a`, with the ACL file already written; on `main`, without the file, every ACL-file case fails too). |
| `RedisAclIT` (ecomdemo-app, new) | 10 | `redis:8-alpine` with the manifest's arguments and ACL file, over TLS: each service's client (its ConfigMap's settings, no password) logs in as its own user (Redis names it in a FLUSHALL refusal) and Boot's Redis health is UP; catalog's certificate may SET/GET/UNLINK `product::*` and is refused DEL, the assistant's and the limiter's keys, SELECT 1, FLUSHALL and CONFIG; app's (ecomdemo) touches no key; customer's certificate (cluster CA, no user) gets NOAUTH; AUTH to any other user or with the old shared password is WRONGPASS; the readiness probe is `cache-admin`; the `cert-reload` sidecar's own script loads a changed ACL file and the new rule applies without a restart. |
| `CatalogCacheAclIT` (catalog-service, new) | 2 | catalog's real `CacheConfig` cache manager and `ProductCacheEvictor` as the user `catalog`: get, put, evict, clear, the stock-changed eviction; the keys are where the smoke test looks; Redis's `ACL LOG` is empty; the user is refused other services' keys, database 1 and FLUSHALL. |
| `AssistantRedisAclIT` (assistant-service, new) | 2 | the real `RedisChatMemoryRepository` (save with MULTI/EXEC, list with SCAN, read, replace, delete) and `PendingActions` (propose, take once) as `assistant`; `ACL LOG` empty; refused the cache, the limiter's keys, FLUSHALL and KEYS. |
| `RateLimitAclIT` (gateway-service, new) | 2 | the gateway's real rate limiter (database 1, the Lua script) as `gateway`: a concurrent burst of 400 gets 429, the buckets exist in database 1, `ACL LOG` empty; refused the cache, the assistant's keys and FLUSHALL. |
| `RedisClientAuthIT` (changed) | 8 | Phase 36's handshake tests on the Phase 37 Redis: the certificate alone gives PONG (was: with the password); a client that still sends the old shared password is refused with WRONGPASS (was: no password gives NOAUTH); the in-pod tools use a `cache-tls` with CN `cache-admin`. |
| `RedisClientAuthConfigTest` (changed) | 15 | no longer expects `--requirepass`. |

**What the per-service ITs found** (the first ACL, written from reading the code, was wrong three times): Spring
Data Redis 4 deletes cache entries with UNLINK, not DEL, and writes evictions asynchronously (`writeAsynchronously()`),
so the refusal never reached the test thread and the keys simply stayed; `expire(Duration)` inside the assistant's
MULTI is PEXPIRE (EXECABORT); the rate limiter's keys are `request_rate_limiter.{<route>.<key>}.*`. Redis's
`ACL LOG` named each one.

**Counter-checks (mutations of the ACL file):** without `+keys` for catalog, `CatalogCacheAclIT` fails (the clear
leaves its keys); without `+evalsha +eval` for the gateway, `RateLimitAclIT` fails: every one of the 400 requests is
served (503 from the closed upstream), none gets 429, because the limiter fails open.

**A flake, not this phase's:** the first full run failed one case of `PostgresTlsIT.renewalIsReloaded` (Phase 35,
PostgreSQL; its 30 s window timed out with the renewed files not yet in use). It passed twice when run alone and in
the second full run. Recorded as KI-076, not fixed here.

## 2. Smoke test additions, validated against a container

`scripts/k8s-smoke.sh` could not run here. The Phase 36/37 Redis block of `scripts/smoke-test.sh` was cut out
verbatim and run with a `kube` shim (`kube get secret` → JSON of test certificates from a test CA with each
service's CN, `kube logs` → `docker logs --since 60s`, `ctr_exec` → `docker exec`) against a Redis container started
with `k8s/data/cache.yaml`'s arguments, the CA and a `cache-admin` certificate at `/etc/ecomdemo-tls` and the ACL
file at `/etc/ecomdemo-acl`:

| Check | Phase 37 Redis | `main`'s Redis (password, no ACL) |
|---|---|---|
| Redis refuses a TLS client without a client certificate / one from another CA | PASS ×2 | PASS ×2 |
| each client's certificate, with no password, gets PONG | PASS ×4 | PASS ×4 (the container's `REDISCLI_AUTH` supplied the password) |
| catalog's, gateway's, assistant's user may read its own key | PASS ×3 | PASS ×3 |
| each client's user is refused another service's key (NOPERM) | PASS ×4 | FAIL ×4 (as it should) |
| catalog-service's Redis user is refused FLUSHALL | PASS | FAIL (as it should) |
| a cluster certificate whose CN is not a Redis user gets NOAUTH | PASS | FAIL (as it should) |

15/15 on the Phase 37 Redis; 6 fail on `main`'s. `scripts/test-smoke-*.sh`: all four pass. The Caching section's
"Redis refuses a client that has no password" now runs on compose only (the cluster has no password).

## 3. Not run, and why

- ⚠️ **`scripts/k8s-up.sh` and `scripts/k8s-smoke.sh`** (kind with cert-manager): this container cannot build a kind
  cluster. The owner runs `scripts/k8s-up.sh` (it removes `SPRING_DATA_REDIS_PASSWORD` from the four services'
  Secrets; cert-manager reissues `cache-tls` with CN `cache-admin`), then `kubectl -n ecomdemo rollout restart
  deploy` (the four Redis clients change only in their Secrets; until they restart they send the old password and
  are refused), then `scripts/k8s-smoke.sh`. That run is the only end-to-end check of the `remove_key` patch, of
  the reissued `cache-tls` and of the services under their ACL users on the cluster.
- ⚠️ **An ACL change on the cluster** (edit the ConfigMap, `kubectl apply -k k8s/`, wait for the sidecar's
  "reloaded the Redis ACL"): covered by `RedisAclIT` on files, not tried with the kubelet's ConfigMap update.
- The compose smoke test: compose is unchanged; CI runs it on this PR (`run-smoke`).
- `performance-tests` compile: no API change.

## 4. Clean-up

All test and validation containers removed; the background dockerd started for this session keeps running.
