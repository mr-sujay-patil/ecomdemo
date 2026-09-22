# Phase 13 Test Report: Caching

- **Date:** 2026-09-22
- **Branch:** `feature/phase-13-redis`
- **Toolchain:** Spring Boot 4.1.1 (`spring-boot-starter-cache`, `spring-boot-starter-data-redis`,
  Lettuce), spring-data-redis 4.1.1, Redis 8 (`redis:8-alpine`), Testcontainers 2.0.5,
  JDK 21, Docker 29.7.2, Compose v5.4.0
- **Result:** ✅ all green. **Three real bugs were found by running it rather than reading it**, and
  they are the most useful part of this report — §4.

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 190, Failures: 0, Errors: 0, Skipped: 0     (surefire, unchanged)
Tests run: 38,  Failures: 0, Errors: 0, Skipped: 0     (failsafe, was 30)
BUILD SUCCESS
```

The eight new integration tests are `CacheApiIT`. No existing test was changed.

**One Redis and one PostgreSQL for the whole run**, counted from the log:

```
grep -c "Creating container for image: redis"    -> 1
grep -c "Creating container for image: postgres" -> 1
```

That is the Phase 7 lesson applied. The Redis container is declared in the shared
`IntegrationTest` configuration, so every `*IT` class asks for the same context. Declaring it on
one test class would have given that class a context of its own — and a second PostgreSQL along
with it, because the cache key is the whole annotation set, not the part that changed.

## 2. The fast suite still needs no Docker

```
./mvnw clean test  ->  Tests run: 190, Failures: 0, Errors: 0, Skipped: 0
"Creating container" lines: 0
```

`spring.cache.type=none` in the `test` profile makes `@Cacheable` a no-op there. That is not a gap
being papered over: caching behaviour is a claim about a *real* cache — that a second read skips
the database, that an update evicts, that a `BigDecimal` survives a JSON round trip — and none of
it can be proven against a HashMap standing in for Redis. It is proven in `CacheApiIT` instead.

## 3. "Done when": repeated reads skip the database, and updates invalidate the cache

✅ Both, and proven in a way that cannot be faked by a timing assertion.

**The technique matters.** Asserting that a read is "fast", or that a key exists, shows the cache
was *written*; it shows nothing about whether anything was *served* from it. So these tests change
the database **behind the cache's back**, with direct SQL that no eviction can see, and then read
through the API. If the old value comes back, the read never reached PostgreSQL.

| Test | What it establishes |
|---|---|
| `repeatedReadsSkipTheDatabase` | Read → change the price to 9999 with SQL → read again → still 1500. Empty the cache → 9999. The middle read cannot have touched the database. |
| `theListingIsCachedAsAWhole` | A row inserted with SQL is invisible in `GET /api/products` until the listing is evicted — the cost of caching a collection under one key, made explicit. |
| `updateWritesThroughToTheCache` | After a `PUT`, the price is moved to 1.00 behind the cache and the read still returns 549.00 — so `@CachePut` *wrote the new value in*, rather than merely evicting and getting lucky on a re-read. |
| `deleteEvictsTheEntry` | The next read is a genuine 404, not a cached product that no longer exists. |
| `theCacheIsLegible` | `product::N` exists, holds JSON containing the name and price, and has a positive TTL. |
| `bigDecimalKeepsItsScale` | `42.50` comes back as `42.50` with `scale() == 2`, not `42.5`. |
| `checkoutIgnoresTheCache` | **The one the phase turns on** — see §5. |
| `theOversellGuardStillHoldsWithACacheInFront` | With the cache warm, a second checkout of the last unit is still 409. |

## 4. Three bugs, found by running it

### 4.1 The serializer wrote what it could not read

A generic serializer with Jackson *default typing* seemed the obvious choice. It stored a
root-level `List` as a bare JSON array with no type id, and then demanded one on the way back:

```
SerializationException: Could not read JSON: Unexpected token (START_OBJECT),
expected VALUE_STRING: need String, Number or Boolean value that contains type id
```

Every cached listing read failed — the cache wrote something it could not read back, and only on
the *second* request, because the first was a miss that went to the database.

**Fix:** one serializer per cache, typed to exactly what that cache holds. Each cache holds one
type, so there is nothing polymorphic to resolve. That also removed a security problem it would
have been easy not to notice: "the document names the class to instantiate" is a deserialization
gadget, and Jackson's own convenience method for it is called `enableUnsafeDefaultTyping`. The
stored documents are now smaller too, with no `@class`:

```
productList::all -> [{"id":6,"name":"Laptop Stand",...,"price":2199.00,...}, ...]
```

### 4.2 A 500 on a public endpoint arrived as 401

While that bug was live, `GET /api/products` — an endpoint with `permitAll()` — returned **401**.
Not 500. The reason is worth keeping:

Spring forwards an unhandled exception to `/error`, **that forward goes through the security
filter chain like any other dispatch**, and `anyRequest().authenticated()` answered it. So a
server error on a public endpoint reached the client as "authentication required", which sends
whoever is debugging it a long way in the wrong direction.

**Fix:** `/error` is now permitted. This was latent since Phase 8 and only became visible when
something finally threw.

### 4.3 A broken cache took the endpoint down

The default `CacheErrorHandler` rethrows, so a cache problem became a failed request — for a cache
the application did not need in order to answer. That is backwards for cache-aside: the database
is still perfectly capable.

**Fix:** a `CacheErrorHandler` that logs and falls through. **Verified by stopping Redis:**

```
docker compose stop cache
curl -o /dev/null -w "%{http_code}" localhost:8080/api/products   ->  200

WARN c.e.cache.CacheConfig : cache GET failed on productList for key all
                             — falling through to the database: ...
```

The asymmetry is recorded in the code: a swallowed *evict* leaves a stale entry, and only the TTL
clears it. That is why every cache here has one.

## 5. What is deliberately NOT cached

`ProductService.requireProduct` — the method the cart and checkout use — is not cached, and this
is the most important decision in the phase.

It returns the **managed JPA entity**. A cached copy would carry a stale `stockQuantity` *and* a
stale `version`, which is worse than ordinary staleness: optimistic locking could not catch it,
because the version it compares would itself have come from the cache. The Phase 6 oversell bug
would return in a form the Phase 6 race test could not detect, since both threads would agree on
the same wrong number.

`checkoutIgnoresTheCache` proves the boundary holds:

1. Create a product with stock 3 and read it, warming the cache.
2. Set the real stock to 1 with SQL.
3. The catalogue still reports **3** — the accepted staleness.
4. Buy 1. Checkout must see the live **1** and succeed.
5. The database must hold **0**, not 2. A cached read of 3 would have left 2.

The rule, stated once: **cache what is read often and changes rarely; never cache what a decision
is made against.** Stock is the second kind.

`productService.save` — the checkout write path — also evicts nothing, on purpose. It runs once per
line inside a transaction that may still roll back; evicting there would discard good entries on
every failed checkout and, worse, evict *before* the commit, letting a concurrent read repopulate
the cache from pre-commit state.

## 6. Smoke test — `scripts/smoke-test.sh`

```
Summary: 136 passed, 0 failed, 0 skipped
```

**136 checks, was 125.** Zero skips, so every cache assertion really ran against Redis rather than
being skipped for a missing `redis-cli`:

```
Caching
  PASS  a cache probe product is created
  PASS  no cache entry before the first read
  PASS  reading the product returns 200
  PASS  and the cache key now exists
  PASS  the entry is readable JSON
  PASS  the entry expires on its own (TTL 600s)
  PASS  updating the product returns 200
  PASS  the cache entry is refreshed, not stale
  PASS  and the API serves the updated value
  PASS  deleting the product evicts the entry
  PASS  and the product is really gone
```

The checks ask Redis directly, because a cache that is never read from and one that is never
written to look identical through the API. It finds the container the same way the database checks
do, and SKIPs loudly if neither `redis-cli` nor a container is reachable.

## 7. An observation worth recording

`CacheManager.getCache(name).clear()` logged and returned **without error while the keys
survived**. `@CacheEvict` on an explicit key works correctly — `deleteEvictsTheEntry` and
`updateWritesThroughToTheCache` both depend on it.

The application never calls `clear()`, so nothing in production depends on this; but a test built
on it would "start from an empty cache" without doing so, and then pass or fail for reasons
unrelated to what it checks. `CacheApiIT` deletes the keys directly instead, and says why.

## 8. Clean-up

- The application stack is running and healthy: `app`, `db`, and now `cache`.
- The SonarQube stack from Phase 12 is also still up; `docker compose -f compose.sonar.yaml down`
  reclaims ~2 GB.
- Redis holds whatever the smoke test left; it is a cache, and `docker compose restart cache`
  empties it with no consequence.
- No stray Java processes.
