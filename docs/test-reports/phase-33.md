# Phase 33 Test Report: Authentication Hardening

- **Date:** 2026-09-30
- **Branch:** `feature/phase-33-auth-hardening`
- **Machine:** the WSL2 workstation. Claude Code produced every local result below on that machine.
  CI results are GitHub Actions runs on the PR.
- **Toolchain:** Java 21.0.12, Maven 3.9.16, Docker 29.8.1, Spring Boot 4.1.1, PostgreSQL 18,
  OpenSSL 3.5.5.
- **Result:** ✅ green.
  - `./mvnw clean verify`: BUILD SUCCESS, **697 tests** (521 unit, 176 integration), 0 failed,
    0 skipped. Up from 673.
  - Compose, cold (`down -v`, `--build`, `.smoke-state` removed), the user's `.env` with the new
    signing key and client secrets: smoke **467 / 0 / 0** (10 new checks). The first two cold runs
    failed; see §3.
  - Kubernetes (kind, `SKIP_BUILD=1 scripts/k8s-up.sh`, all 9 Deployments restarted): smoke
    **423 / 0 / 7**. The 7 skips are the known ones (observability stays in compose, and the AI
    generation checks).

## 1. Done when

| Claim | Proven by |
|---|---|
| No service except customer-service holds a signing key | `JwtKeyConfig` builds only a JWKS decoder; `ecomdemo.jwt.secret` and `JWT_SECRET` are gone from every service, compose and k8s. `TokenServiceTest.aDifferentKeyDoesNotVerify`, `JwksKeyRotationTest.aForeignKeyIsRejected`, smoke: "a token signed with any other key is rejected (401), even claiming ADMIN" |
| A key rotation works without downtime | `JwtConfigTest.rotationKeepsOldTokensValid` (customer-service verifies old and new tokens during the overlap); `JwksKeyRotationTest.rotationNeedsNoRestart` (a verifier fetches the set again for an unknown `kid`) |
| Repeated wrong passwords are throttled | `LoginThrottleIT` (5 per username, 20 per client, reset on success, PostgreSQL); `AuthServiceTest` (checked before BCrypt, counted, cleared); `AuthControllerTest` (429 + `Retry-After`, last `X-Forwarded-For` hop); smoke: "the next attempt is throttled: 429" + Retry-After |

## 2. Smoke additions ("Authentication hardening")

| Check | Compose | kind |
|---|---|---|
| customer-service publishes its public keys at `/oauth2/jwks` | PASS | PASS |
| and no private key material | PASS | PASS |
| a token signed with any other key is rejected (401), even claiming ADMIN | PASS | PASS |
| the gateway gets a service token scoped to `catalog:read` only | PASS | PASS |
| with it, the gateway may read the catalogue (200) | PASS | PASS |
| a service token cannot call outside its scope: reading stock is 403 | PASS | PASS |
| and asking payment-service to settle an order is 403 | PASS | PASS |
| five wrong passwords are five ordinary 401s | PASS | PASS |
| the next attempt is throttled: 429 | PASS | PASS |
| with a Retry-After of a few seconds, and the same number in the message | PASS | PASS |

The existing check "the header names the signing algorithm" now expects `RS256`, not `HS256`: the
phase changes it on purpose, and no check was removed.

## 3. What went wrong on the way (and was fixed)

- **catalog-service needed `inventory:write`, not only `inventory:read`.** The first cold compose
  smoke: 330 passed, dozens failed, starting with "an ADMIN creating the same product returns 201"
  (500). catalog-service sets a product's stock level in inventory-service on create, update and
  delete, and its token could only read. Every later failure depended on creating a product. The
  Java suite could not see it, because catalog-service's tests use an in-memory inventory. Fixed in
  customer-service's client registry; `ServiceClientRegistryTest` now pins every client's scopes.
- **A parser, not the system.** The second cold run: 465 / 2 / 0. The two out-of-scope checks read
  the status by word position, and on an error BusyBox `wget` prints "server returned error:
  HTTP/1.1 403", so the second word was "server". The status is now taken from after `HTTP/x.x`,
  tried against the running stack (200, 403, 403) before the third run.
- **The test decoder lost to the real one.** Every service configuration has a default JWKS URI, so
  in tests its real decoder existed and tried to fetch keys from a customer-service that was not
  running (the gateway tests returned 500). The test auto-configuration's decoders are now
  `@Primary`, except in customer-service, which keeps its own keys.
- **The user's step.** The first attempt at adding the secrets to `.env` lost the signing key (the
  multi-line command's continuation did not survive the paste); a one-line command fixed it. The key
  was checked as present and parseable (an RSA 2048 private key) without being read out.
- **Expected ERROR lines.** The app logged 2 ERROR lines during the compose run and 6 on kind: Kafka
  send failures while the smoke test's outbox scenario (Phase 18) stops Kafka on purpose. The event
  stays pending and is published when Kafka is back, which that scenario asserts.

## 4. ⚠️ Needs you

Nothing more to set up: the signing key and the three client secrets are in your `.env`, and
`k8s-up.sh` generates its own for kind (kept across runs, added to existing Secrets). To see it:

```bash
docker compose up -d --build --wait
docker exec ecomdemo-catalog-service wget -qO- http://customer-service:8083/oauth2/jwks   # public keys only
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -w '%{http_code} ' -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"nobody","password":"wrong-password"}'; done; echo
# → 401 401 401 401 401 429
curl -s localhost:8083/actuator/prometheus | grep '^ecomdemo_auth_login'
```

To rotate the key, follow the three steps in the README ("Signing, service identities and login
throttling").
