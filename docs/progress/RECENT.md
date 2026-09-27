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

## Phase 20d: Microservices Split - the last two services (tag: phase-20-complete, PRs #29-#32)
**What exists now:** FIVE deployables, sixteen containers, five databases. `customer-service` owns
`users` (customer_db, 8083/5435) and is the only issuer of user tokens; `notification-service` owns
`notification` + `processed_event` (notification_db, 8085/5436) and has no business API at all;
`catalog-service` and `inventory-service` as before; `ecomdemo-app` is order-service in all but name,
keeping only cart/orders/outbox/batch plus two public proxies. Smoke **275 passed / 0 failed** from a
COLD stack on `main`; **1459 MiB of 3916**. Verified and tagged 2026-09-26 at `f819d3e`.
**Key code:** `common/.../jwt/CurrentUser` (reads CLAIMS, no repository); `common/.../clients/customer/`;
`ecomdemo-app/.../identity/` (the auth + customer proxies); `customer-service/` and
`notification-service/` whole modules; V15 (snapshot + backfill username, drop the user FKs) and V16
(drop users/notification/processed_event).
**Config & infrastructure:** `CUSTOMER_BASE_URL`; customer-db 5435, notification-db 5436; 320M caps on
the two new services; Alloy and Prometheus cover all five; the Dockerfile copies five module poms.
**Tests:** OrderPlacedConsumerIT (publishes a MAP, not its own record), CustomerIntegrationTest (the one
base that CAN log in), ProductProxyAccessTest, EveryModuleWithIntegrationTestsRunsThemTest.
**❗ Gotchas - read `docs/test-reports/phase-20d.md` §0 first.** Failsafe was never bound in the four
extracted services, so their *IT tests had NOT RUN since 20b - `verify` printed BUILD SUCCESS the whole
time, because an unbound plugin reports nothing. Binding them found five real defects. Also: a
package-private `@BeforeEach` is not inherited across packages; the resource server needs its OWN
authenticationEntryPoint or a bad token returns an empty body; `TokenView` guessed its field names and
produced a 400 that blamed the caller for a response-side error; and the outbox relay stops the batch at
the first failure, so a warm-up row left pending during an outage starves the row under test.
**Follow-ups (not done):** rename `ecomdemo-app` to order-service (cosmetic, touches every image tag);
Phase 21's gateway replaces both proxies and removes the plaintext password from the app's memory; the
Phase 19 dashboard defect is now the oldest open item; a failed compensating release still leaks a
reservation; HS256 shared secret.
