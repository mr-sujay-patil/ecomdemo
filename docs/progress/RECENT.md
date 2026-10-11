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

## Phase 37: Redis ACL Users (tag: phase-37-complete, PR #TBD)
**What exists now:** in k8s, Redis logs each connection in as the ACL user named by its client certificate's CN
(`--tls-auth-clients-user CN`) and authorizes it by `--aclfile /etc/ecomdemo-acl/users.acl` (ConfigMap `cache-acl`,
`k8s/data/cache-acl.yaml`). Users: `default` off; `cache-admin` (all; CN of `cache-tls`, the Redis pod's own tools);
`ecomdemo` (no keys), `catalog` (`product::*`, `productList::*`), `gateway` (`request_rate_limiter.*`, db 1, script),
`assistant` (`assistant:memory:*`, `assistant:action:*`); commands named one by one. NO password in k8s Redis
(no `requirepass`, users have no password and are not `nopass`). Compose unchanged (password, `default`).
**Key code:** `common` test-jar `com.ecomdemo.support.RedisAcl` (the ACL file; a plain Redis with test passwords;
`ACL LOG`). The `cert-reload` sidecar also runs `ACL LOAD` when the mounted ACL file changes.
**Config & infrastructure:** `scripts/k8s-up.sh` no longer creates the Redis password and removes
`SPRING_DATA_REDIS_PASSWORD` from app/catalog/gateway/assistant Secrets (`remove_key`); `cache-secrets` left unused.
Existing cluster: `k8s-up.sh`, then `kubectl -n ecomdemo rollout restart deploy` (a pod with the old env sends AUTH
→ `default` off → refused). Still 25 certificates (`cache-tls` gains `commonName`).
**Tests:** `RedisAclConfigTest` (19: args, mount, users = CNs, default off, no passwords, least privilege, key
prefixes read from the code, k8s-up, smoke); `RedisAclIT` (10, TLS: each service is its user + health UP, catalog
confined, unknown CN NOAUTH, AUTH cannot switch, probe is cache-admin, sidecar ACL LOAD); `CatalogCacheAclIT`,
`AssistantRedisAclIT`, `RateLimitAclIT` (real code under the user, `ACL LOG` empty). `RedisClientAuthIT`/
`RedisClientAuthConfigTest` moved to no password. Smoke (k8s): per client PONG without password, own key OK,
another's NOPERM, catalog FLUSHALL refused, customer's CN NOAUTH.
**Gotchas:** a new Redis key prefix or command needs a line in `cache-acl.yaml` (else NOPERM in k8s only, and the
cache/rate limiter HIDE it: read `ACL LOG`). Spring Data Redis 4 deletes with UNLINK and evicts asynchronously;
`expire(Duration)` is PEXPIRE; rate-limit keys are `request_rate_limiter.{<route>.<key>}.tokens|timestamp`.
**Follow-ups (not done):** ACLs in compose (KI-072); app's unused Redis client (KI-073); catalog's KEYS clear
(KI-074); async evictions invisible to the error handler (KI-075).

## Phase 36: Redis Client Certificates (tag: phase-36-complete, PR #91)
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
