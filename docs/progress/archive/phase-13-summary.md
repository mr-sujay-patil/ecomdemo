# Phase 13 Summary (archived)

> Moved out of `docs/progress/RECENT.md` when Phase 15's summary was added.

## Phase 13: Caching (tag: phase-13-complete, PR #15)
**What exists now:** The catalogue is served from Redis. `GET /api/products` and
`/api/products/{id}` are `@Cacheable`; create/update/delete keep the cache honest. Per-cache TTL
(product 10 min, listing 2 min), JSON values typed per cache, hit/miss logging, and a
CacheErrorHandler so a Redis outage costs latency rather than availability. 228 tests
(190 + 38), smoke test 136 checks. Schema still V6.
**Key code:** `cache/CacheConfig` (one `JacksonJsonRedisSerializer` per cache typed to what it
holds, TTLs, `CachingConfigurer.errorHandler()`, a `RedisCacheManager` whose `decorateCache`
wraps everything in `LoggingCache`), `cache/CacheNames`, `cache/LoggingCache`.
`ProductService`: `@Cacheable` on `findAll`/`findById`, `@Caching(put=@CachePut, evict=@CacheEvict)`
on `update`, `@CacheEvict` on `create`/`delete`. **`requireProduct` and `save` are deliberately
uncached** - they are the cart/checkout path. `SecurityConfig` now permits `/error`.
**Config & infrastructure:** New deps `spring-boot-starter-cache` + `spring-boot-starter-data-redis`
(Lettuce). `compose.yaml` gains a `cache` service (`redis:8-alpine`, `--save "" --appendonly no`,
`maxmemory` + `allkeys-lru`, no volume, healthcheck, app `depends_on: service_healthy`); the app
reaches it at `cache:6379`. `application-dev.properties` gets `spring.data.redis.*` with 2s
timeouts. `test` profile: `spring.cache.type=none`. `it` profile: `spring.cache.type=redis`.
**Tests:** +8 `cache/CacheApiIT`, all proving behaviour by changing the database BEHIND the cache
with direct SQL and checking which value comes back. New `support/RedisContainerConfig`
(`GenericContainer` + `@ServiceConnection(name="redis")`) imported by `IntegrationTest`.
Test report: `docs/test-reports/phase-13.md`.
**Gotchas:** A generic serializer with Jackson default typing wrote a root-level List as a bare
array and then demanded a type id on read - every cached listing read failed. Per-cache types fix
it AND remove the deserialization-gadget risk. That 500 reached clients as **401**, because Spring
forwards to `/error` and the forward goes through the filter chain - latent since Phase 8. The
default CacheErrorHandler rethrows, so a cache bug became an outage. `CacheManager.clear()` logged
success while the keys survived (`@CacheEvict` on explicit keys is fine); tests delete keys
directly. Declaring Redis anywhere but the shared `IntegrationTest` config would fork a second
PostgreSQL too - verified one of each across the whole Failsafe run.
**Follow-ups (not done, out of scope):** cache hit-rate metrics - Phase 15. Making the browsing
view's stock accurate without the evict-before-commit race. Redis `requirepass` and a replica.
Redis data types beyond string-with-TTL (hashes, sorted sets, streams) need a RedisTemplate.
