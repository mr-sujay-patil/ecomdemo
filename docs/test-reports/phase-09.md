# Phase 09 Test Report: JWT Authentication

- **Date:** 2026-09-22
- **Branch:** `feature/phase-09-jwt`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1,
  Spring Security 7.1.1 (OAuth2 Resource Server + Nimbus JOSE), Testcontainers 2.0.5,
  Surefire 3.5.6, Failsafe 3.5.6, Flyway 12.4.0, PostgreSQL 18.6 (`postgres:18-alpine`,
  aarch64), Docker 29.7.2, curl 8.7.1, bash 3.2.57
- **Result:** ✅ every automated check passed. Nothing is deferred to manual verification.

## 1. Full regression — `./mvnw clean verify`

```
[INFO] --- surefire:3.5.6:test ---
Tests run: 190, Failures: 0, Errors: 0, Skipped: 0

[INFO] --- failsafe:3.5.6:integration-test ---
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
Total time: 24.032 s
```

Run three times in a row, all green (25.1 s / 23.3 s / 24.0 s). Nothing is `@Disabled` or
`@Ignore`d anywhere in `src/test`, and Skipped is 0 in both suites. No migration was added this
phase, so the schema stays at V6.

**Unit and slice suite: 190, was 163.** The 27 new tests:

| Class | Tests | What it covers |
|---|---:|---|
| `auth/TokenServiceTest` | 6 | The claims a token carries (`sub`, `uid`, `roles`), the `ROLE_` prefix stripped on the way out, the configured expiry, the payload being readable with no key, a hand-edited payload failing verification, and a token signed with a different key being rejected |
| `auth/AuthControllerTest` | 5 | Login reachable anonymously and returning a token, the password never echoed, a wrong password and an unknown username producing *identical* 401 bodies, a missing field being 400 before the service is reached |
| `security/CurrentUserTest` | 6 | Id and username read from claims with **no database call**, an `Integer` claim narrowing correctly to `Long`, `require()` loading the managed entity, and three failure modes (no `uid`, no authentication, anonymous) |
| `security/JwtConfigTest` | 5 | A token round-tripping through this application's own encoder and decoder, a foreign issuer rejected despite a valid signature, an expired token rejected, a key under 256 bits refused **at startup**, and a random key generated when `JWT_SECRET` is unset |
| `auth/AuthServiceTest` | 4 | Delegation to the `AuthenticationManager`, no token issued on failure, and `LoginRequest.toString()` hiding the password |
| `common/OpenApiDocumentationTest` | +1 net | `bearerAuth` declared with `bearerFormat: JWT`, `basicAuth` **gone**, and `/api/auth/login` documented as public |

**Integration suite: 30, was 23.** `AuthApiIT` is new (8); `ProductApiIT` drops from 8 to 7
because its bad-credentials test moved to `AuthApiIT`, where the login endpoint now lives.

## 2. "Done when": secured endpoints work with a Bearer token, and expired or tampered tokens are rejected

✅ Settled in `AuthApiIT`, over real HTTP against a real PostgreSQL, and again by the smoke test
against the running application.

| Claim | Where | Result |
|---|---|---|
| A token from login opens a protected endpoint | `AuthApiIT.loginReturnsAWorkingToken` | ✅ 200, and the profile is the right account |
| Every other `*ApiIT` works on Bearer tokens | `ProductApiIT`, `CartApiIT`, `OrderApiIT` (22 tests) | ✅ the whole Phase 8 rule matrix still holds, now token-driven |
| A **tampered** token is rejected | `AuthApiIT.aTamperedTokenIsRejected` | ✅ 401 — a real CUSTOMER token rewritten to claim `ADMIN` |
| An **expired** token is rejected | `AuthApiIT.anExpiredTokenIsRejected` | ✅ 401 — correctly signed with the running application's key, lifetime entirely in the past |
| Malformed rubbish is rejected | `AuthApiIT.aMalformedTokenIsRejected` | ✅ 401, not 500 |
| A token from another issuer is rejected | `JwtConfigTest.refusesAForeignIssuer` | ✅ a valid signature is not on its own a reason to trust a token |

A note on how the expired case is tested honestly. Waiting for a real token to expire would mean
waiting out the decoder's 60-second clock-skew allowance as well, so both tests mint a token whose
*whole lifetime is two hours in the past* — correctly signed, using the application's own
`JwtEncoder`. That is a stronger test than a short expiry would be: it proves the refusal comes
from the clock and not from the signature.

## 3. Application start — `./mvnw spring-boot:run`

Started twice, once each way, because the key handling is the one piece of configuration this
phase adds.

**With `JWT_SECRET` set** (`JWT_SECRET='a-local-development-signing-key-32+'`):

```
Successfully validated 6 migrations (execution time 00:00.019s)
Current version of schema "public": 6
Started EcomdemoApplication in 2.9 seconds
```

No warning, and **0 ERROR** lines.

**With `JWT_SECRET` unset**, the application still starts — there is no manual setup step — and
says exactly what it did:

```
WARN com.ecomdemo.security.JwtConfig : JWT_SECRET is not set, so a random signing key was
generated for this run. Logins work, but EVERY TOKEN BECOMES INVALID WHEN THIS APPLICATION
RESTARTS, and a second instance would reject tokens issued by this one. Set JWT_SECRET to at
least 32 characters before running anything you expect to keep working.
```

**0 ERROR** lines in both runs. The only other WARNs are springdoc's two standing advisories.

Also confirmed: Spring Boot's "Using generated security password" line does not appear — the
application's own user store replaced that default back in Phase 8.

## 4. Smoke test — `scripts/smoke-test.sh`

```
Summary: 125 passed, 0 failed, 0 skipped
SMOKE TEST PASSED
```

**125 checks, was 110.** Run four times: twice against the `JWT_SECRET` instance, once more to
confirm re-runnability, and once against the generated-key instance. Zero skips.

Every check the phase file asks for, and where it is:

| "Smoke test additions" | Check | Result |
|---|---|---|
| Login returns a JWT | `login returns a token for the admin and both customers`, plus the structure checks below | ✅ |
| The full flow runs with a Bearer token | Every section — happy path, negatives, persistence, the oversell race, data ownership — now sends `Authorization: Bearer` | ✅ |
| A tampered token → 401 | `a tampered token returns 401` and `a tampered ADMIN claim buys nothing` | ✅ |
| An expired token → 401 | `an expired token returns 401` | ✅ |

Beyond the required list the script also checks the shape of the thing it was given: three
dot-separated segments, `alg: HS256` in the header, a payload that decodes **without any key** and
contains the username and roles but never the password, and a lifetime between 1 and 3600 seconds.

One detail worth recording about the expired-token check. Minting a *correctly signed* expired
token needs the signing key, so the script uses `JWT_SECRET` when it is present in its own
environment and falls back to an unsigned one otherwise. The server refuses both with 401, but
only the first proves that **expiry** caused it — so the script says which path ran rather than
implying the stronger claim:

```
PASS  the expired token was correctly signed, so expiry alone caused the refusal
PASS  the expired token was unsigned (JWT_SECRET unset); set it to test expiry specifically
```

## 5. Manual verification against the running application

| Check | Result |
|---|---|
| A 401 carries no `WWW-Authenticate` header | ✅ absent — Spring Security's resource-server default would have sent `Bearer error="invalid_token"` |
| 401/403 bodies are `ApiError` | ✅ `{"status":401,"message":...}` |
| Security headers still arrive | ✅ `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `X-XSS-Protection: 0` |
| OpenAPI declares Bearer, not Basic | ✅ `bearerAuth` = `{type: http, scheme: bearer, bearerFormat: JWT}`; `basicAuth` gone; 10 paths |
| A token's payload is readable with no key | ✅ `{"iss":"ecomdemo","sub":"smoke-customer","uid":2,"exp":…,"iat":…,"roles":["CUSTOMER"]}` |

**The measurement that explains the phase.** Five calls each, against the running application:

```
login (verifies BCrypt): 106 ms/call
authenticated GET      :  14 ms/call
```

That ~90 ms gap is the BCrypt verification. Under HTTP Basic it was paid on **every single
request**; now it is paid once per login, and every later call costs a signature check over bytes
the request already carries. The slowness is not a defect — it is what makes a stolen password
table expensive to attack — which is exactly why it belongs at login and nowhere else.

## 6. The fast suite still needs no Docker

```
./mvnw clean test  ->  Tests run: 190, Failures: 0, Errors: 0, Skipped: 0
Total time: 10.980 s
```

Zero "Creating container" lines and no `*ApiIT` class ran.

## 7. Clean-up

- Application stopped; no stray `java` or `spring-boot:run` processes.
- No Testcontainers containers left behind. `ecomdemo-postgres` is left **running** at schema
  **v6**, as the next phase expects.
- `users` holds the three accounts from Phase 8 (`admin`, `smoke-customer`, `smoke-customer-b`)
  plus `it-auth-fresh`, created by `AuthApiIT` inside its own throwaway container and therefore
  not in the dev database.
- `.smoke-state` holds the current persistence probe, as it has since Phase 4.

## 8. Nothing deferred

Every "Done when" item and every "Smoke test addition" has an automated check that was actually
run. There is no ⚠️ item in this phase.

The refresh token endpoint listed as *optional* in the phase file was **not built**, per the
project's "implement only this phase's scope" rule. It is suggested in the PR instead.
