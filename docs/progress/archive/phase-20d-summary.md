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
