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

## Phase 33: Authentication Hardening (tag: phase-33-complete, PR #54)
**What exists now:** customer-service alone signs tokens, RS256 with a `kid`; everyone else verifies
with its public keys from `/oauth2/jwks` (no shared secret anywhere). Services get their own tokens
from `POST /oauth2/token` (client credentials, own secret) with scopes: gateway `catalog:read`,
catalog-service `inventory:read inventory:write`, ecomdemo-app all five. Logins are throttled per
username (5) and per client (20) in 15 min: 429 + Retry-After, block 30 s doubling to 15 min.
**Key code:** common `jwt`: `JwtKeyConfig` (JWKS decoder, only if `ecomdemo.jwt.jwk-set-uri`),
`JwtAuthorities.authorities()` (roles → ROLE_, scope → SCOPE_), `ServiceTokens` (scope constants,
`authority()`), `ServiceTokenProvider` (interface) + `ClientCredentialsTokenProvider` (cached).
customer: `security.SigningKeys`/`SigningKeyProperties`/`JwtConfig`, `auth.OAuth2Controller`,
`ServiceClientProperties`, `auth.throttle.*` (V2 `login_throttle`). Gateway: reactive JWKS decoder in
`GatewayJwtConfig`; `ServiceIdentityFilter` fetches on boundedElastic.
**Config & infrastructure:** customer: `JWT_SIGNING_KEY` (PKCS#8 base64), `JWT_SIGNING_KEY_ID`,
`JWT_NEXT_SIGNING_KEY(_ID)`, `JWT_ACTIVE_KEY_ID`, `GATEWAY/APP/CATALOG_CLIENT_SECRET`. Others:
`JWT_JWK_SET_URI`; callers also `SERVICE_TOKEN_URI`, `SERVICE_CLIENT_SECRET`; app client id
`ecomdemo-app`. k8s-up.sh: .env → kept → generated, patches missing keys into existing Secrets.
Meters `ecomdemo_auth_login_failures_total`, `ecomdemo_auth_login_throttled_total{key}`.
**Tests:** 697 (521 unit, 176 IT). Test-jar `TestJwt` (per-JVM RSA key; `user`, `service`, `sign`,
`*SignedBy`) + `TestJwtAutoConfiguration` (@Primary decoders, not in customer-service; fixed service
token). New: `ClientCredentialsTokenProviderTest`, `JwksKeyRotationTest`, `OAuth2ApiIT`,
`LoginThrottleIT`, `ServiceClientRegistryTest`; scope tests in Inventory/PaymentSecurityTest,
ProductApiIT. Smoke section "Authentication hardening": compose 467/0/0, kind 423/0/7.
**Gotchas:** a new service call needs its scope in customer-service's `service-clients` AND
`ServiceClientRegistryTest`; catalog's in-memory inventory hides scope mistakes (only the compose
smoke found `inventory:write`). Web slices import `TestJwtAutoConfiguration` via `WithSecurityRules`.
Smoke checks that need a service token run inside a container (BusyBox wget).
**Follow-ups (not done):** refresh tokens and revocation (KI-017); per-client throttle trusts the last
X-Forwarded-For hop, forgeable on a published customer port (KI-003); KI-039, KI-040 still open.

## Phase 32: Saga Timeouts and Reconciliation (tag: phase-32-complete, PR #51)
**What exists now:** the order service owns the saga's clock. `SagaDeadlineSweeper` (@Scheduled, every
`ecomdemo.saga.sweep-interval` 10s) reconciles orders PENDING longer than `ecomdemo.saga.deadline` (1m,
env `ORDER_SAGA_DEADLINE`): payment `settle` → COMPLETED confirms; FAILED/VOIDED → inventory `close`
→ cancel; no answer → DEFERRED (stays PENDING). Admin DLT list/replay with audit. Two alerts.
**Key code:** app `order.internal.saga`: `SagaReconciler`, `SagaDeadlineSweeper`, `OrderDecisions`
(confirm/cancel shared with `OrderSagaHandler`), `SagaParticipants` + `HttpSagaParticipants` (own
RestClients, 1s/5s timeouts), `SagaProperties`, `SagaMetrics` (public, for DashboardMetricsTest). New
module `deadletter` (`DeadLetterService`, `/api/admin/dead-letters`, V20 `dead_letter_replay`).
payment: `POST /internal/saga/orders/{id}/settle` (SERVICE only, 2nd security chain `@Order(1)`),
status VOIDED (V2). inventory: `POST /api/inventory/orders/{id}/close`, `closed_order` (V4),
`OrderLocks` (pg_advisory_xact_lock per order) taken by `reserveForOrder` and `closeOrder`.
**Config & infrastructure:** app env `PAYMENT_BASE_URL` (compose + k8s), `ORDER_SAGA_DEADLINE`. Meters
`saga_orders_overdue`, `saga_reconciliations_total{outcome=confirmed|cancelled|deferred|already_decided}`.
Alerts `SagaOrdersStuck` (10m), `SagaDeadlineResolvingOrders` (fires ~15m after any smoke run).
**Tests:** 651 (505 unit, 146 IT). +`SagaReconcilerTest`, `SagaDeadlineIT` (FakeSagaParticipants can
LOSE_ORDER_CREATED / LOSE_STOCK_RESERVED / LOSE_PAYMENT_REPLY, PAYMENT_UNREACHABLE; its `Settlement`
is @Primary `SagaParticipants`), `DeadLetterIT`, payment/inventory settle/close/fence/race tests.
Smoke section "Saga deadline and dead letters" (stops payment, forges a DLT with kafka CLI, resets
the payment-service group offset): compose 429/0/0, kind 385/0/7.
**Gotchas:** `it`/`test` profiles set `ecomdemo.saga.sweep-enabled=false`; tests call
`sweep(Instant)`. `FlywayMigrationTest` and the smoke test PIN the app's migration list: add each new
V there. A `@DataJpaTest` importing `InventoryService` needs `OrderLocks` too. Advisory locks are
PostgreSQL-only: inventory's close/reserve can't run on H2.
**Follow-ups (not done):** KI-037 (a dead-lettered StockRejected is cancelled with the void's reason);
KI-038 (sweep in every instance, safe); KI-001 Swagger/OpenAPI fix next; restore cart on cancel (KI-019).
