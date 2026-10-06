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

## Phase 34: Product Images (tag: phase-34-complete, PR #TBD)
**What exists now:** `ProductResponse.imageUrl` (nullable, additive; also in search hits via `ProductSearchHit.product`)
is the gateway-relative path `/api/products/{id}/image`. The endpoint is public through the gateway, with
ETag/304, `Cache-Control: public, max-age=86400`, nosniff, CORP cross-origin, a CSP on SVG. Seed-only: 8 of
the 10 seeded products have an SVG, products 9 and 10 have none; products created via the API have none.
**Key code:** catalog-service `Product.imageFile` (V5 `image_file`, no setter), `dto.ProductResponse.imageUrl`,
`ProductImageService` (plain-name + extension allow-list, classpath `product-images/`, SHA-256 ETag, cached),
`ProductImage` record, `internal.ProductImageController` (`WebRequest.checkNotModified`).
**Config & infrastructure:** none new. Images: `catalog-service/src/main/resources/product-images/`, written
by `scripts/generate-product-images.py`. No gateway or security-rule change: `GET /api/products/**` was already public.
**Tests:** 724 (542 unit, 182 IT). New: `ProductResponseTest`, `ProductImageServiceTest`, `ProductImageFilesTest`
(type, 256 KiB cap, inert SVG, V5 names only shipped files), `ProductImageControllerTest`; image tests in
`ProductApiIT` and `EdgeSecurityIT`. Smoke section "Product images" (12 checks): compose cold 480/0/0, kept volumes
482/0/0, kind 436/0/7.
**Gotchas:** catalog-service is internal: its image path needs a token, anonymous access is the gateway's
(tests say so). A new image file needs a V-migration naming it AND the extension on the allow-list. `ProductResponse`
is cached in Redis (product 10 min, list 2 min): after deploying onto a warm Redis, entries written before
this phase read `imageUrl` as null until they expire. Self-heals; a cold stack (`down -v`) never sees it.
**Follow-ups (not done):** admin upload of images (storage, size and content validation, cleanup); thumbnails or
`srcset` variants if raster images arrive; a versioned URL so caching could be `immutable`.

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
