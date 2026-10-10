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
