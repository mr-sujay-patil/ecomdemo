# Phase 08 Test Report: Spring Security

- **Date:** 2026-09-22
- **Branch:** `feature/phase-08-spring-security`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1,
  Spring Security 7.1.1, Testcontainers 2.0.5, Surefire 3.5.6, Failsafe 3.5.6, Flyway 12.4.0,
  PostgreSQL 18.6 (`postgres:18-alpine`, aarch64), Docker 29.7.2, curl 8.7.1, bash 3.2.57
- **Result:** ✅ every automated check passed. Nothing is deferred to manual verification.

## 1. Full regression — `./mvnw clean verify`

```
[INFO] --- surefire:3.5.6:test ---
Tests run: 163, Failures: 0, Errors: 0, Skipped: 0

[INFO] --- failsafe:3.5.6:integration-test ---
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
Total time: 31.793 s
```

Run three times in a row, all green (36.1 s / 31.4 s / 31.8 s). Nothing is `@Disabled` or
`@Ignore`d anywhere in `src/test` (`grep` returns 0 matches), and Skipped is 0 in both suites.

**Unit and slice suite: 163, was 120.** The 43 new tests are:

| Class | Tests | What it covers |
|---|---:|---|
| `customer/CustomerControllerTest` | 11 | register 201 anonymously, 400 on a short password or an illegal username, 409 on a duplicate, a body carrying `"role":"ADMIN"` still produces a CUSTOMER, the response never echoes the password, `/me` 200 as customer and as admin, 401 anonymous |
| `customer/CustomerServiceTest` | 8 | the stored value is the encoder's output and not the typed password, the role is never taken from the request, a duplicate is a 409 before BCrypt is paid for, a constraint violation is also a 409, BCrypt salting makes two hashes of one password differ, the profile comes from the security context |
| `security/AppUserDetailsServiceTest` | 4 | the `ROLE_` prefix is added at the adapter and not stored, the principal carries the account id, the credential is the stored hash, an unknown username throws |
| `product/ProductControllerTest` → `Access` | 5 | reads public anonymously and as a customer, writes 401 anonymous / 403 as customer, the 403 body is `ApiError` |
| `cart/CartControllerTest` → `Access` | 3 | 401 anonymous, 403 as admin, the standard error shape |
| `order/OrderControllerTest` → `Access` | 4 | 401 anonymous and 403 as admin on all three endpoints, both bodies `ApiError` |
| `cart/CartRepositoryTest` | +1 | `findByUserId` never returns another account's cart |
| `order/OrderRepositoryTest` | +1 | `findAllByUserIdWithItems` leaves another account's orders out |
| `common/FlywayMigrationTest` | +2 | V5 seeds exactly one ADMIN whose stored value is a 60-character `$2a$10$` hash that really matches `admin123`; V6's `user_id` columns are NOT NULL, the cart has its UNIQUE constraint, `idx_orders_user` exists |
| `common/OpenApiDocumentationTest` | +2 | the document declares `basicAuth`, and marks exactly the protected operations |

**Integration suite: 23, was 15.** `ProductApiIT` 8 (was 5), `CartApiIT` 8 (was 6),
`OrderApiIT` 7 (was 4). The new ones are listed in §4.

## 2. "Done when": `@WithMockUser` tests cover allowed and denied access for each role

✅ Covered, in the three controller slices' `Access` nests — 12 tests in total.

`@WithMockUser` does not log anybody in: it puts a ready-made `Authentication` into the
`SecurityContext` before the request, so the `UserDetailsService` and the `PasswordEncoder` are
never reached. That is the right tool for testing *rules*, and it is why no password appears in
those files.

One thing had to be got right for these tests to mean anything. **`@WebMvcTest` auto-configures
Spring Security but does not pick up this application's `SecurityFilterChain`** — a slice scans
web components, and a `@Configuration` class is not one. Left alone, the slice runs against
Spring Boot's fallback chain ("every request must be authenticated"), which answers 401 to
everything; a test asserting "anonymous gets 401" would then pass while proving nothing about
the rules that were actually written. `support/WithSecurityRules` imports the real
`SecurityConfig` (and the three `ApiError*` beans it is built from) so the slices test the real
thing. That the ADMIN/CUSTOMER distinctions come out differently per endpoint is the evidence
the import took effect.

The matrix the 12 tests assert:

| Endpoint | anonymous | CUSTOMER | ADMIN |
|---|---|---|---|
| `GET /api/products`, `GET /api/products/{id}` | 200 | 200 | 200 |
| `POST/PUT/DELETE /api/products` | 401 | 403 | allowed |
| `GET/POST/PUT/DELETE /api/cart/**` | 401 | allowed | 403 |
| `POST /api/orders`, `GET /api/orders`, `GET /api/orders/{id}` | 401 | allowed | 403 |
| `POST /api/customers/register` | allowed | allowed | allowed |
| `GET/PUT /api/customers/me` | 401 | allowed | allowed |

The same matrix is asserted a second time in `*ApiIT` with **real credentials over real HTTP**
(§4), and a third time by the smoke test against the running application (§5).

## 3. Application start — `./mvnw spring-boot:run`

Started against the live Phase 7 database (PostgreSQL 18.6 in `ecomdemo-postgres`), which was at
schema v4. The two new migrations applied **incrementally to data that was already there**:

```
Current version of schema "public": 4
Migrating schema "public" to version "5 - add users"
Migrating schema "public" to version "6 - cart and orders per user"
Successfully applied 2 migrations to schema "public", now at version v6 (execution time 00:00.034s)
Started EcomdemoApplication in 2.939 seconds
```

A later restart re-validated rather than re-applying:

```
Successfully validated 6 migrations (execution time 00:00.014s)
Current version of schema "public": 6
Started EcomdemoApplication in 2.633 seconds
```

**0 ERROR lines in the log.** The only WARNs are springdoc's two standing advisories about
`/v3/api-docs` and `/swagger-ui.html` being enabled, and the expected optimistic-lock warning
from the checkout race.

Note what did *not* appear: Spring Boot's "Using generated security password: <uuid>" line. That
default in-memory user is switched off by declaring a `UserDetailsService` bean, which is how you
can tell from the log alone that the application is using its own user store.

## 4. Integration tests — real credentials over real HTTP

The `*ApiIT` tests now send an `Authorization: Basic ...` header that is verified against the
BCrypt hash migration V5 put into the container's database, by the real filter chain. This is the
only layer where the rules and an actual login are exercised together; `@WithMockUser` skips the
authentication it is supposed to be testing.

New tests:

- `ProductApiIT.readsArePublic` — the unauthenticated template gets 200 on the list and on a
  single product.
- `ProductApiIT.writesRequireAnAdmin` — 401 anonymous, 403 as a customer, and the catalogue is
  unchanged by either attempt.
- `ProductApiIT.badCredentialsAreRefusedWithoutLeakingWhichPartWasWrong` — a wrong password and
  a username that does not exist produce **byte-identical** 401 bodies. A difference would be a
  free username-enumeration oracle.
- `CartApiIT.cartEndpointsRequireACustomer` — 401 anonymous, 403 as the admin.
- `CartApiIT.cartsAreNotSharedBetweenAccounts` — two shoppers, two cart ids; one adding an item
  leaves the other's cart empty. Before this phase there was one cart row for the whole world and
  this assertion would have found the other shopper's items sitting in it.
- `OrderApiIT.anotherCustomersOrderIsNotReadable` — customer B asking for customer A's order by
  id gets 403, the order is absent from B's list, and A can still read it.
- `OrderApiIT.checkoutRequiresACustomer` — 401 anonymous, 403 as the admin.
- `OrderApiIT.anOrderKnowsWhoPlacedIt` — `username` on the response is the account that checked
  out.

The Phase 6 oversell race still runs here, now as one shopper's two simultaneous checkouts:
`Tests run: 7, Failures: 0` in `OrderApiIT`, with the versioned UPDATE losing once on a Tomcat
thread.

## 5. Smoke test — `scripts/smoke-test.sh`

```
Summary: 110 passed, 0 failed, 0 skipped
SMOKE TEST PASSED
```

**110 checks, was 78.** Run three times: twice against one application instance and once after a
restart (which also satisfied the persistence-across-restarts check, "the probe product from the
previous run is still there (id=75)"). Zero skips means the `psql` path was available, so the
schema assertions really ran rather than being skipped.

Every check the phase file asks for, and where it is:

| "Smoke test additions" | Check | Result |
|---|---|---|
| Anonymous product read → 200 | `anonymous GET /api/products returns 200` | ✅ |
| Anonymous cart access → 401 | `anonymous GET /api/cart returns 401` + the body is `ApiError` | ✅ |
| A customer creating a product → 403 | `a CUSTOMER creating a product returns 403` | ✅ |
| …and an admin → 201 | `an ADMIN creating the same product returns 201` | ✅ |
| Customer A cannot read customer B's order | `customer B cannot read customer A's order` → 403, and it is absent from B's list | ✅ |
| The full flow runs as a logged-in customer | every check in "Happy path", "Negative cases", "Persistence", "Transactions and concurrency" and "Data ownership" now runs authenticated | ✅ |

Beyond the required list, the script also checks that a wrong password and an unknown username
produce the same message, that registration never grants ADMIN, that an administrator is refused
on the cart (403), that every row in `users` holds a 60-character `$2a$10$` hash, that
`cart.user_id` is NOT NULL, and that no order has a null `user_id`.

The script is **re-runnable**: registration accepts 409 as readily as 201, so a second run
against a database that already has the accounts is green (verified — run 2 printed
"a customer account exists (registered, or already present)").

One portability detail worth recording: the auth plumbing is written without bash arrays, because
macOS still ships bash 3.2, where expanding an empty array under `set -u` is an unbound-variable
error. `request` spells the four cases out instead.

## 6. Manual verification against the running application

| Check | Command | Result |
|---|---|---|
| 401 carries no `WWW-Authenticate` | `curl -i /api/cart` | ✅ header absent; browsers will not pop up their native login dialog |
| 401/403 bodies are `ApiError` | same | ✅ `Content-Type: application/json`, `{"status":401,"message":...}` |
| Security headers arrive | same | ✅ `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-cache, no-store...` — contributed by the filter chain, free with the starter |
| The role cannot be chosen at registration | `POST /api/customers/register` with `"role":"ADMIN"` | ✅ `"role":"CUSTOMER"` in the response |
| …and that account really cannot write | `POST /api/products` as it | ✅ 403 |
| Profile update | `PUT /api/customers/me` | ✅ `fullName` changed, `username` and `role` unchanged |
| Duplicate registration | the same username twice | ✅ 409 |
| OpenAPI declares Basic auth | `GET /v3/api-docs` | ✅ `components.securitySchemes.basicAuth` = `{type: http, scheme: basic}`, 9 paths documented |
| Swagger UI reachable | `GET /swagger-ui.html` | ✅ 302 to `/swagger-ui/index.html`, which is 200 (checked by the smoke test) |

Database state afterwards, read directly with `psql`:

```
admin           | ADMIN    | $2a$10$ | 60
smoke-customer  | CUSTOMER | $2a$10$ | 60
smoke-customer-b| CUSTOMER | $2a$10$ | 60

orders with user_id IS NULL : 0
carts: admin 0, smoke-customer 1, smoke-customer-b 1
```

Every stored credential is a 60-character BCrypt hash; no order belongs to nobody; each shopper
has exactly one cart and the administrator has none (they have never used one — the endpoint
refuses them).

## 7. The fast suite still needs no Docker

```
./mvnw clean test  ->  Tests run: 163, Failures: 0, Errors: 0, Skipped: 0
Total time: 11.363 s
```

Zero "Creating container" lines and no `*ApiIT` class ran. The Phase 7 split holds: the inner
loop stays fast and Docker-free, and `verify` is where the container work happens.

## 8. Clean-up

- Application stopped; no stray `java` or `spring-boot:run` processes.
- The `escalation-probe` account created during §6 was deleted; `users` is back to the three
  accounts above.
- No Testcontainers containers left behind. `ecomdemo-postgres` is left **running** at schema
  **v6**, as the next phase expects.
- `.smoke-state` holds the current persistence probe, as it has since Phase 4.

## 9. Nothing deferred

Every "Done when" item and every "Smoke test addition" has an automated check that was actually
run. There is no ⚠️ item in this phase.
