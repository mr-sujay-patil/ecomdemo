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
