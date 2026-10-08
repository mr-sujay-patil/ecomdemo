# Security (Phase 31)

Two things live here:

1. **What CI checks.** Every dependency and every image is scanned for known vulnerabilities, and a
   HIGH or CRITICAL finding fails the build.
2. **An OWASP API Security Top 10 (2023) review of this system.** It was checked against the code
   and, where it mattered, against the running stack.

## 1. Scanning in CI

| Job | Tool | Looks at | Fails on | Output |
|---|---|---|---|---|
| `dependency-scan` | OWASP Dependency-Check 13.0.0 | every Maven dependency of the services (test scope excluded), against the **NVD** | CVSS >= 7.0 | `dependency-check-report` artifact (HTML, JSON) |
| `image-scan` | Trivy 0.74.0 (image pinned by digest) | all 8 service images: Alpine packages, the JRE, the packaged jars, against GitHub's and the distributions' advisories | HIGH or CRITICAL, fixable or not | `sbom-cyclonedx` artifact: one CycloneDX SBOM per image |

`publish` needs **both** scans as well as the tests, so no image reaches GHCR while either scan
is red.

**Why two scanners.** They see different things. Dependency-Check reads the Maven dependency tree
and matches it to the NVD via CPE names. Trivy reads what is actually *inside the image*, including
the operating system packages and the JRE, which no Maven tool can see. It uses a different
advisory database. Each can miss what the other catches.

**Run them locally:**

    NVD_API_KEY=... ./mvnw org.owasp:dependency-check-maven:aggregate    # report: target/dependency-check-report.html
    docker build --build-arg MODULE=catalog-service -t scan/catalog-service:ci .
    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.74.0 \
        image --severity HIGH,CRITICAL scan/catalog-service:ci

The NVD API key is free (https://nvd.nist.gov/developers/request-an-api-key). CI reads it from the
`NVD_API_KEY` repository secret and never from a file.

**Keeping the NVD data warm.** The full database is ~400 000 records (~240 MB), and a cold download
has taken anywhere from 26 minutes to a stall of over an hour, depending on the NVD's API that day.
So the download isn't left to the scans:

- The `NVD data` workflow (`.github/workflows/nvd-data.yml`) updates it daily, and on demand, on
  `main`. A cache saved on `main` is readable by every branch; one saved by a pull request is
  readable only by that pull request.
- `dependency-scan` restores that copy and tops it up in its own step, which logs progress.
- It then scans with `-DautoUpdate=false`, and has a 60-minute limit.
- Only complete updates on `main` are saved.

### Suppression policy

**Fix first.** That means a newer version, or overriding the version Spring Boot manages through
its property in the root pom. Suppress only:

- a **false positive** (the CPE matched a different product), or
- a vulnerability in code this project provably does not reach.

Every suppression records **why** and **until when**:

- `dependency-check-suppressions.xml`: `<notes>` and `until=`
- `.trivyignore.yaml`: `statement` and `expired_at`

After the date the finding fails the build again, so a suppression can't quietly become permanent.
Today there are two Dependency-Check suppressions, both false positives (below), and none for
Trivy.

### Findings and what was done

| Finding | Where | Severity | Found by | Action |
|---|---|---|---|---|
| CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 | `tomcat-embed-core` 11.0.24 (7 images) | CRITICAL | Trivy | **Fixed**: `tomcat.version` 11.0.25 |
| CVE-2026-68497 | `tools.jackson.core:jackson-databind` 3.1.5 (8 images) | HIGH | Trivy | **Fixed**: `jackson-bom.version` 3.1.6 |
| CVE-2026-68497 | `com.fasterxml.jackson.core:jackson-databind` 2.21.5 (7 images) | HIGH | Trivy | **Fixed**: `jackson-2-bom.version` 2.21.6 |
| CVE-2026-91776, CVE-2026-91777 | `tools.jackson.core:jackson-databind` 3.1.6 (8 images) | HIGH | Trivy (CI on `main`, 2026-10-01) | **Fixed** (KI-042): `jackson-bom.version` 3.1.7 |
| CVE-2026-91776, CVE-2026-91777 | `com.fasterxml.jackson.core:jackson-databind` 2.21.6 (8 images) | HIGH | Trivy (CI on `main`, 2026-10-01) | **Fixed** (KI-042): `jackson-2-bom.version` 2.21.7 |
| CVE-2026-53914 (9.8) | `kotlin-stdlib` 2.3.21, `kotlin-stdlib-common` 1.9.10, `kotlin-reflect` 2.3.21 | CRITICAL | Dependency-Check | **Suppressed, false positive**: the CVE is in Kotlin's *build cache* (compiler tooling), and the CPE covers the whole product, so every Kotlin jar matches. Only the runtime libraries ship here (via OkHttp and the OpenAI client); Trivy doesn't flag them. Expires 2027-03-31 |
| CVE-2026-18022 (8.8) | `com.pgvector:pgvector` 0.1.6 (Java client) | HIGH | Dependency-Check | **Suppressed, false positive**: the CVE is in the PostgreSQL *extension's* IVFFlat build, on 32-bit only. The jar has no index code, and the running extension was checked: 0.8.6 (fixed), HNSW index, 64-bit. Expires 2027-03-31 |

All three overrides are for versions Spring Boot 4.1.1 (the newest release) manages. Each is one
patch release in the same line, and each should be removed when a Boot release catches up. The
comment in the root pom says so.

**A scan that passes today can fail tomorrow with no code change.** The two Jackson properties
have each been raised twice: the second pair of advisories (KI-042) was published about ten hours
after Phase 33 merged with a green scan, and showed up on the next push to `main`, a docs-and-tests
PR. Until KI-044 the scans ran only on pushes and pull requests, so a new disclosure was noticed by
whoever merged next. Now `ci.yml` also runs **both scans daily at 04:43 UTC** (an hour after
`nvd-data.yml` refreshes the NVD copy) and on demand (Actions > CI > Run workflow): a scheduled run
builds, tests and publishes nothing. A red scheduled run is how a new CVE shows up on its own day;
GitHub emails whoever last changed the schedule, and the run's page lists the failing findings.

After the fixes, **all 8 images scan clean** at HIGH/CRITICAL, and the Alpine base had no
HIGH/CRITICAL findings to begin with. **Dependency-Check** covered 142 dependencies and passes
with the two suppressions above.

**Below the gate (MEDIUM, recorded, not failing the build)**, from Dependency-Check. These are
revisited when the libraries update:

| Finding | Where | CVSS |
|---|---|---|
| CVE-2026-89044 | `netty-transport` 4.2.17 | 6.5 |
| CVE-2020-29582 | `kotlin-stdlib-common` 1.9.10 (the CPE range matches the whole product; the bug was fixed in 1.4.21) | 5.3 |
| CVE-2026-54285 | `opentelemetry-api` 1.62.0 | 5.3 |
| CVE-2026-39882, -40894, -41178, -44967, -54285 | `opentelemetry-proto` 1.10.0-alpha | 5.3 |
| CVE-2026-41115 | `kafka-clients` 4.2.1 | 4.3 |

## 2. OWASP API Security Top 10 (2023) review

Status key:

- ✅ **addressed**: in place, with where
- ⚠️ **gap**: a real weakness, with a recommended fix
- 🟡 **accepted**: a known limit of a learning project, written down

### API1 Broken Object Level Authorization ✅

Can a user reach *another user's* object by changing an id?

- **Orders:** `GET /api/orders/{id}` and `/{id}/status` check ownership in the app, and answer 403
  for another customer's order. The smoke test proves it with two customers.
- **Cart:** the cart has no id in its URL. It's always the caller's, taken from the token.
- **Profile:** `GET /api/customers/me` has no id parameter.
- **Assistant:** it calls downstream services with the **caller's own** token (Phase 29), so it
  can't see more than the customer could. It even reports 403 and 404 identically, so the
  customer can't learn which order ids exist.

### API2 Broken Authentication ✅ / 🟡 (hardened in Phase 33)

- ✅ Passwords are stored with **BCrypt** (8-72 characters, as BCrypt reads at most 72 bytes).
- ✅ JWTs are **RS256** (Phase 33): customer-service alone holds the RSA private key; every other
  service verifies with its PUBLIC keys from `/oauth2/jwks`, so verifying no longer means being
  able to mint. The algorithm is fixed on the verifying side, the issuer is checked, tokens last
  15 minutes, and every token names its key (`kid`), so a key rotates without downtime
  (`JwksKeyRotationTest`). Until Phase 33 one shared HS256 secret sat in every service.
- 🟡 **No refresh tokens and no revocation.** A stolen token works until it expires, up to 15
  minutes.
- ✅ **Logins are throttled** (Phase 33): 5 failures per username or 20 per client address in
  15 minutes block further attempts for 30 s, doubling up to 15 minutes, with `429` and
  `Retry-After`. Counted in PostgreSQL (every replica sees the same counts), checked before BCrypt.
  Throttling, not lockout, so nobody can lock an account by typing its name wrong. Before, only the
  general rate limit applied: roughly 4 million guesses a day from one address.
  (`LoginThrottleIT`, the smoke test.)
- 🟡 The per-client counter trusts the last `X-Forwarded-For` hop, which our gateway appends. A
  caller that reaches customer-service's port directly (published in compose, KI-003) can forge it;
  in Kubernetes the port is internal.

### API3 Broken Object Property Level Authorization ✅

Can a client read or write a *field* it shouldn't?

- Requests are **records with only the fields a client may set.** `RegisterRequest` has no
  `role`, so a client can't register itself as ADMIN (no mass assignment). `ProductRequest` has
  no `id`.
- Responses are separate records. `CustomerResponse` has no password hash.
- Errors are `ApiError` (status, message, path). Stack traces are never included, which is Spring
  Boot's default, and nothing overrides it.

### API4 Unrestricted Resource Consumption 🟡 / ⚠️

- ✅ A gateway **rate limit** on every route: per user (authenticated) or per IP (anonymous),
  50/s with a burst of 100.
- ✅ **Upload limit:** 16 MB on the CSV import.
- ✅ **Assistant limits:** input capped at 1 000 characters, at most 5 tool calls per message,
  and a model timeout.
- 🟡 **Timeouts and circuit breakers:** on the app's catalog calls, but not on inventory calls or
  the gateway's `/api/products` route (both carried since Phase 22).
- ⚠️ `GET /api/products` returns the **whole catalogue**, unpaginated. That's harmless at 40
  products, but it grows without limit. Recommended: pagination with a maximum page size.
- ⚠️ **No load shedding at checkout.** Phase 30 showed an overload waiting 10 s per request for a
  database connection instead of failing fast. Recommended: a concurrency limit that answers 503
  with `Retry-After`.
- 🟡 **No per-user budget for AI calls,** which cost money on a paid provider. This is a
  follow-up from Phase 29.

### API5 Broken Function Level Authorization ✅ (found and fixed in this phase)

Can a user call a function meant for a different role?

**At the gateway:** it always was enforced.

- `/api/admin/**`, `/api/inventory/**` and product writes need ADMIN.
- Cart, orders and the assistant need CUSTOMER.
- `anyExchange().authenticated()` is the fallback.
- `EdgeSecurityIT` covers these rules.

**Behind the gateway:** not everywhere, until this phase.

- ecomdemo-app and payment-service already checked roles themselves.
- *Phase 32:* payment-service gained its first endpoint, `POST /internal/saga/orders/{id}/settle`,
  which the saga deadline uses. It needs a SERVICE token (401 without one, 403 with a shopper's), it
  sits on its own security chain so `/api/**` stays deny-all, and the gateway does not route
  `/internal`. Settling can void an unpaid order but never charges one. inventory-service's new
  `POST /api/inventory/orders/{id}/close` is covered by its existing ADMIN-or-SERVICE rule, and the
  dead-letter replay API is under `/api/admin/**` (ADMIN at the gateway and in the app).
- **catalog-service and inventory-service only required *a* valid token.** They relied on the
  gateway for the role, a decision from Phase 21, when the edge was meant to be the only way in.

The review tested that on the running compose stack, with a **CUSTOMER** token sent straight to
the service ports and no data changed:

| Request (CUSTOMER token, bypassing the gateway) | Before | After the fix |
|---|---|---|
| `GET /api/inventory/1` on :8080 (the gateway) | 403 | 403 |
| `GET /api/inventory/1` on :8082 (inventory-service) | **200** | 403 |
| `PUT /api/inventory/1` same level on :8082 | **200**, the write is accepted | 403 |
| `POST /api/products` (empty body) on :8081 (catalog-service) | **400**: past authorization, stopped only by validation | 403 |
| `DELETE /api/products/999999` on :8081 | **404**: past authorization; an existing id would be deleted | 403 |
| `POST /api/admin/batch/product-import` on :8084 (app) | 403 | 403 |

How far it reached depended on how the stack was run:

- **Kubernetes:** these services are `ClusterIP` only, so an attacker needed to be inside the
  cluster already.
- **compose:** every service port was published on `0.0.0.0` (see API8), so anyone who could reach
  the machine could do it. Since KI-003 they are bound to `127.0.0.1`.

**The fix** is defence in depth: each service now repeats the gateway's rule for its own paths.

- **catalog-service:** a person's token may read. Writes and the embedding backfill need ADMIN or
  a service scope (below). The gateway's own token for anonymous browsing may only read.
- **inventory-service:** every path needs ADMIN or a service scope. No shopper has a reason to call
  it at all; checkout and catalog call it with their service tokens.
- **Tests:**
  - `InventorySecurityTest`: a CUSTOMER gets 403 on read, write and reserve; an ADMIN may write.
  - `ProductApiIT`: a CUSTOMER may read but gets 403 on create, delete and the backfill; an ADMIN
    and the SERVICE identity may write.

✅ **Scoped service identities (Phase 33).** Until then one SERVICE role let any service call
anything any service could, and with the shared HMAC secret (API2) any service could mint it.
Now each service gets its token from customer-service with its own client secret (OAuth2 client
credentials), carrying only its scopes, and each callee checks them:

| Caller | Scopes | Refused, for example |
|---|---|---|
| gateway-service | `catalog:read` | writing the catalogue, reading stock, settling a payment |
| catalog-service | `inventory:read inventory:write` | reading or writing the catalogue as a service, settling a payment |
| ecomdemo-app | `catalog:read catalog:write inventory:read inventory:write payment:settle` | - |

Proven by `InventorySecurityTest`, `ProductApiIT` and `PaymentSecurityTest` (an out-of-scope token
gets 403) and by the smoke test, which asks for the gateway's real token inside its container.

#### Public by design: product images (Phase 34)

`GET /api/products/{id}/image` is readable with no token, like the rest of the catalogue's reads: a
browser `<img>` cannot send one, and a picture of something for sale is the shop window. The
gateway permits it (`GET /api/products/**`) and presents its own `catalog:read` token to
catalog-service, which stays internal; a request straight to catalog-service without a token is
`401`. Writing to that path is ADMIN-only at the edge, and no endpoint writes images at all.
Why serving a file is safe here:
- **No client-supplied path.** The product id selects a row, the row's `image_file` selects the
  file. Even that name is re-checked: a plain file name (no separator, so no `../`) with an
  allow-listed extension (`svg png webp jpg jpeg`), else a 500 and a log line, never an open.
- **The type comes from the extension, not the bytes**, and `X-Content-Type-Options: nosniff` stops
  the browser guessing another one.
- **SVG can carry script.** The shipped files are inert (a test rejects `<script`, event handlers,
  links and external references), and every SVG response carries
  `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`, so opening
  one as a document runs nothing.
- **Embedding is intended**, so `Cross-Origin-Resource-Policy: cross-origin`; nothing sensitive is
  in an image. Rate limiting is the gateway's, per client, like every route (API4).

### API6 Unrestricted Access to Sensitive Business Flows 🟡

- Nothing limits **how many orders** one account places, or how fast beyond the general rate
  limit. A bot could buy up a scarce product (scalping).
- Nothing limits **how many accounts** one client registers.

Both are business decisions, such as a per-customer quantity limit or a CAPTCHA on registration.
Recorded, not built.

### API7 Server-Side Request Forgery ✅

No endpoint fetches a URL a client supplies. Every outbound address comes from configuration:
service base URLs, `OLLAMA_BASE_URL`, the OpenAI endpoint. The assistant's tools call fixed
internal paths with the product name or order id as a *parameter*, never as a URL.

### API8 Security Misconfiguration ⚠️

- ✅ **compose publishes every port on `127.0.0.1` only (KI-003).** It used to publish all of them
  on every interface: the eight services, the six PostgreSQL databases (with default passwords),
  Redis and Kafka (no authentication), which is what made the API5 gap reachable. Every mapping is
  now `${BIND_ADDRESS:-127.0.0.1}:<host>:<container>`; this machine's browser, `psql` and `curl` are
  unaffected (including from Windows to WSL2, checked), and other machines are refused. Verified by
  connecting to this machine's LAN address: refused on every port, open on loopback; with
  `BIND_ADDRESS=0.0.0.0` the same port was open. `scripts/test-compose-ports.sh` renders the
  compose files and the smoke test's **Published ports** section reads what Docker actually bound.
  Opting out is one variable and is on purpose.
- ⚠️ **The gateway's `/actuator/prometheus` is public.** Metrics reveal route names, error rates
  and JVM details. Recommended: scrape it on an internal port, or require a token.
- ✅ **CSRF off** is deliberate. The API uses bearer tokens, not cookies, so a browser can't be
  tricked into sending credentials. The README's security section explains it.
- ✅ **CORS:** an explicit origin list, methods and headers, and no credentials. Since KI-041 the
  gateway's security chain applies it first, so a preflight is answered (or refused, 403) before
  authentication, from the same `globalcors` configuration the routes use.
- ✅ **Least privilege in CI:** `contents: read` by default, `packages: write` only in `publish`.
- ✅ **Containers:** non-root user, minimal JRE Alpine image, scanned.

### API9 Improper Inventory Management 🟡

- ✅ **One entry point.** The gateway's route list is the inventory of what is public. notification
  has no route on purpose, as it has no API.
- ✅ **SBOMs:** every image's contents are recorded as a CycloneDX SBOM on every CI run.
- ✅ **OpenAPI** (KI-001): every service with a public API publishes its own document, and the
  gateway serves them all in one Swagger UI. The documents are the inventory of the API, next to the
  route list. payment and notification build none, so there is nothing to leak about `/internal/**`.
- 🟡 **No API versioning** (`/api/v1`). There's one client and one version.
- 🟡 **Swagger UI** is served by the gateway, anonymously (GET only), fine for development. A
  production profile should switch it off (KI-027).

### API10 Unsafe Consumption of APIs ✅ / 🟡

- **Service-to-service:** responses are read into typed records, never passed through. The app's
  catalog calls have timeouts, retries and a circuit breaker (Phase 22); inventory calls don't yet
  (see API4).
- **Language models** are treated as untrusted input:
  - Catalog's generated descriptions are validated, and an unusable answer is refused (Phase 27).
  - The assistant's writes need the customer's confirmation.
  - `OrderClaimGuard` replaces answers that claim order data nobody looked up.
  - Tool calls are bounded (Phase 29).

## 3. Concepts

- **CVE and CVSS.** A CVE is an identifier for one publicly known vulnerability. CVSS scores its
  severity from 0 to 10:

  | Score | Severity |
  |---|---|
  | 0.1-3.9 | LOW |
  | 4.0-6.9 | MEDIUM |
  | 7.0-8.9 | HIGH |
  | 9.0-10 | CRITICAL |

  CI fails at 7.0. The score describes the vulnerability in general; whether it matters *here*
  depends on whether the vulnerable code is reachable. That judgement is what a suppression's
  statement records.
- **Supply chain and SBOMs.** Most of the code in an image wasn't written here. An SBOM (Software
  Bill of Materials) lists every component and version. When a new CVE is announced, the question
  "are we affected?" becomes a search instead of an investigation. The scanners, too, are part of
  the supply chain, which is why Trivy is pinned by digest.
- **Shift left.** Find problems as early as possible, on the pull request rather than in
  production. The scans run on every PR, and a red scan blocks both the merge and the publish.
- **The OWASP API Top 10.** The ten most common ways APIs are broken, as ranked by OWASP (2023).
  Section 2 is this system checked against each one.
