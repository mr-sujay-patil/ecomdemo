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

## Phase 22: Resilience (tag: phase-22-complete, PR #TBD)
**What exists now:** every `ecomdemo-app` → catalog-service call goes through
Retry(CircuitBreaker(Bulkhead(HTTP call with a timeout))). catalog-service down: add-to-cart is a 503
+ Retry-After in ~0.6 s, the breaker opens after 5 failed calls (refusals ~20 ms), CHECKOUT STILL
SUCCEEDS (cart snapshot), and it recovers on its own ~10 s after a restart. Grafana dashboard
"EcomDemo Resilience". Smoke **313 / 0** cold on the WSL2 workstation.
**Key code:** `ecomdemo-app/.../resilience/` (`ResilientCatalog`, `CatalogResilienceConfig` - a static
BeanPostProcessor wrapping the HTTP `CatalogClient` IN PLACE, so the ITs' @Primary fake is untouched);
`common/.../clients/catalog/CatalogProperties` (connect/read/bulk-read timeouts, two RestClients);
`common/.../shared/ServiceUnavailableException` → 503 in `GlobalExceptionHandler`.
**Config & infrastructure:** `resilience4j.*.instances.catalog.*` and `ecomdemo.catalog.*-timeout` in the
app's application.properties (250 ms connect, 500 ms read, 30 s bulk; 2 attempts; window 10 / min 5 /
50 %; open 10 s; bulkhead 20). `resilience4j.version` 2.4.0 in the parent. `scripts/failure-demo.sh`.
**Tests:** ResilientCatalogTest (loads the SHIPPED resilience4j.* properties into R4j's own
auto-config; includes the worst-case budget sum), CatalogClientTimeoutTest (real slow HttpServer),
DashboardMetricsTest (+ resilience series from the real binders); smoke §Resilience (18 checks).
**Gotchas:** a STOPPED container is not refused - cached IP → SYN unanswered (connect timeout); expired
cache → name resolution hangs ~12 s (the connect timeout does NOT cover DNS; the read timeout bounds it).
Timeouts compose: attempts × read timeout + backoff must fit the budget. `resilience4j-micrometer` must
not be test-scoped. Windows reserved 9022-9121 (WinNAT) → Prometheus 9090 failed; use PROMETHEUS_PORT.
A VM pause made one `verify` take 86 min.
**Follow-ups (not done):** cold-start poison-message partition stall (seen once in 3 cold runs); the
gateway has no breaker on its own `/api/products` route; notification-service at 97 % of its 320M cap;
resilience for the inventory calls (checkout's real dependency); Phase 21 recorded no decisions.

## Phase 21: API Gateway (tag: phase-21-complete, PR #33)
**What exists now:** SIX deployables, seventeen containers. `gateway-service` (Spring Cloud Gateway
5.0.3, train 2025.1.3, reactive/Netty) owns host port **8080** and routes to all five services; the
app moved to **8084** (white-box checks only). JWT validated at the edge, Redis rate limiter (50/s,
burst 100, per caller), CORS, correlation-ID filter. The app's three proxies are gone, so a login's
plaintext password never passes through `ecomdemo-app`. Smoke **295 / 0**; **1387 MiB of 3916**.
Verified and tagged 2026-09-26 at `f7d5021` (on the Mac).
**Key code:** `gateway-service/.../gateway/` - `GatewaySecurityConfig`, `GatewayJwtConfig`,
`RateLimitConfig`, `CorrelationIdWebFilter`, `ServiceIdentityFilter` (mints a SERVICE token for
anonymously-permitted paths, ordered AFTER security, never replaces a caller's token), `ApiErrors`.
**Config & infrastructure:** `GATEWAY_PORT=8080`, `APP_PORT=8084` in `.env`; routes in
`gateway-service/src/main/resources/application.yml`; the smoke script has `BASE_URL` (gateway) and
`APP_URL` (app, for actuator/api-docs).
**Tests:** EdgeSecurityIT (edge decisions, NO upstreams), gateway 7 unit + 15 ITs; smoke burst check
(one curl, one connection, 300 requests, 40 in flight -> 429s).
**Gotchas:** springdoc types in a scanned `@Configuration` break a service that omits the optional dep;
Maven exclusions are per DECLARATION (a `test-jar` declaration inherited none -> Tomcat at test scope);
`@AutoConfigureWebTestClient` is for MOCK slices; a burst check slower than the refill rate tests the
client, not the limiter. The gateway's actuator answers under the same paths as the app's - point
white-box checks at `APP_URL`.
**Follow-ups (not done):** catalog-service has no springdoc; only `ecomdemo-app` logs structured, so a
correlation ID dies at the hop; the gateway does no request logging; HS256 shared secret; Phase 19
dashboard defect; a failed compensating release leaks a reservation.
