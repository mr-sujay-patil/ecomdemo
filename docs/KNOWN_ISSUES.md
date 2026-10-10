# 🐞 Known Issues

The one list of known defects, gaps, and deferred work. Before this file, they were scattered across
the README's "Known gaps" sections, `docs/architecture/saga.md`, `docs/performance.md`,
`docs/security.md`, `RECENT.md` follow-ups, and code comments. That is how KI-001 went unnoticed from
Phase 21 until Phase 32.

**The rule:** a defect (something that used to work, or should work, and doesn't) is fixed on a
`fix/ki-XXX-<slug>` branch. A new capability or technology is a phase. The lifecycle is in
`docs/process/execution-protocol.md` (section 8).

## Triage values

| Triage | Meaning |
|---|---|
| **Fix** | A defect. Goes through the fix track, one branch and one PR per issue |
| **Phase N** | Covered by an approved phase. Closed by that phase's PR |
| **Candidate** | A new capability. Becomes a phase only if the user approves one |
| **Accepted** | A deliberate limit of a learning project, with the reason written down. No work planned |
| **Needs check** | Recorded as a gap at the time. Not re-verified against the current code; check before triaging |

**Status:** Open · In progress (branch) · Fixed (PR #, tag `ki-XXX-fixed`) · Won't fix (reason)

**Severity:** High (security hole, data loss, or wrong business outcome) · Medium (broken or missing
behaviour with a workaround, or a real risk under a plausible condition) · Low (cleanup, docs, cosmetics)

Adding an issue: take the next free ID, add a row, and add a detail section only when the row can't
carry the scope. Found during a phase or fix? Record it here in that branch; don't fix it in passing.

## Fix: the defect queue (in suggested order)

| ID | Issue | Severity | Since | Source | Status |
|---|---|---|---|---|---|
| KI-001 | Swagger UI and OpenAPI docs are unreachable since the monolith split (details below) | Medium | Phase 21 | `OpenApiDocumentationTest.java:163`, `security.md` API9 | Fixed (PR #52) |
| KI-002 | Two outbox relays double-publish. catalog-service runs 2-4 pods in k8s (`k8s/hpa.yaml`, `minReplicas: 2`), and the relay takes no lock (`SELECT ... FOR UPDATE SKIP LOCKED` missing). Consumers may absorb it by `event_id`; confirm each one does | Medium | Phase 18, exposed by Phase 25 | `OutboxRelay.java:40` | Fixed (PR #65) |
| KI-003 | compose publishes every port on all interfaces: 6 PostgreSQL databases (default passwords), Redis and Kafka (no auth) | Medium | Phase 10 | `security.md` API8 | Fixed (PR #66) |
| KI-004 | No timeouts or circuit breaker on the app's inventory calls or on the gateway's `/api/products` route | Medium | Phase 22 | `security.md` API4, API10 | Fixed (PR #67) |
| KI-005 | No load shedding at checkout: under overload, requests wait 10 s for a connection instead of failing fast with 503 + `Retry-After` | Medium | Phase 30 | `security.md` API4, `performance.md` | Fixed (PR #68) |
| KI-006 | The gateway's `/actuator/prometheus` is public, and reveals route names, error rates and JVM details | Low | Phase 21 | `security.md` API8 | Fixed (PR #69) |
| KI-007 | `GET /api/products` returns the whole catalogue, unpaginated | Low | Phase 1 | `security.md` API4 | Fixed (PR #70) |
| KI-008 | Nothing prunes `processed_event`, which grows forever (its index already exists) | Low | Phase 17 | `architecture/saga.md` | Fixed (this PR) |
| KI-009 | The README's "Known gaps (closed by later phases)" sections are stale: some items are closed, some still open, and the heading says all are closed. Replace them with a link to this file | Low | n/a | `README.md:3989` | Fixed (this PR) |
| KI-010 | notification-service keeps its own copy of the idempotent-consumer code instead of using `ProcessedEvents` from the library | Low | Phase 24 | `ProcessedEvents.java:21`, `saga.md` | Fixed (this PR) |
| KI-011 | Dead code: `InventoryGateway.reserve`/`release` and inventory's matching HTTP endpoints are no longer called by checkout | Low | Phase 24 | `architecture/saga.md` | Fixed (this PR) |
| KI-039 | compose's Kafka keeps nothing across `down`/`up`: the `kafka-data` volume is mounted at `/var/lib/kafka/data`, but the broker writes to `/tmp/kafka-logs`. Every topic, offset and consumer group is lost while the PostgreSQL volumes survive | Medium | Phase 17 | Phase 32 merge verification (2026-09-30): `kafka-log-dirs.sh` reports `/tmp/kafka-logs` | Fixed (PR #55) |
| KI-040 | A dead letter is identified only by its Kafka address. `dead_letter_replay` is unique on `(dlt_topic, dlt_partition, dlt_offset)`, so once a DLT's offsets restart (topic recreated, or KI-039), a new dead letter at a reused offset is refused 409 "already replayed" and can never be replayed. Fails safe (never replays twice). Key it on the record's identity (e.g. the event ID) as well **Reachability after KI-039's fix (2026-10-01):** a smoke run on kept volumes now passes (470/0/0), because the broker keeps its offsets across `down`/`up`; the defect is still there, now reachable only when a dead-letter topic's offsets restart while `dead_letter_replay` keeps its rows (a topic recreated, or only `kafka-data` removed). | Medium | Phase 32 | Phase 32 merge verification (2026-09-30): smoke with kept volumes 429/2 failed | Fixed (PR #63) |
| KI-041 | The gateway answers every CORS preflight (`OPTIONS`) with 401, so a browser app on another origin cannot log in or send any authenticated request (details below) | Medium | Phase 21 | Found 2026-09-30 while writing the frontend integration guide: `curl -X OPTIONS` with `Origin: http://localhost:3000` → 401, no `Access-Control-Allow-*` | Fixed (PR #53) |
| KI-042 | CI's image scan fails on two new HIGH CVEs in jackson-databind, in all 8 images, so `publish` is skipped on `main` (details below) | High | Phase 31 (the scanner); CVEs disclosed after 2026-09-30 | CI run 36815366837 on the PR #55 merge: `Image scan (Trivy)`, step "Scan every image" | Fixed (PR #56) |
| KI-043 | `scripts/smoke-test.sh` does not check that the stack it hits is this checkout's own: it only follows `BASE_URL`. Against another stack it creates accounts, products and orders and, in its failure scenarios, stops Kafka, catalog-service and payment-service. compose's fixed container names and ports make that easy to hit: the frontend team's clone (`~/projects/ecomdemo-backend-readonly`) shares them | Medium | Phase 1 (the smoke test); the shared names since Phase 10 | Found 2026-10-01 during KI-039's merge verification: my `compose up` collided with their stack, and the smoke then ran against it for several minutes before I stopped it | Fixed (PR #58) |
| KI-046 | `LoginThrottleIT.blocksAClientTryingManyUsernames` (customer-service) is flaky: it expects 20 attempts from one client to answer 401 and the 21st to answer 429, but in CI it got 429 before the loop ended (`LoginThrottleIT.java:98`). Passed on re-run, and locally. Cause: `address()` drew 1 of 250 hosts at random, so the second call returned the blocked client's own address one run in 250 (confirmed by forcing a collision) | Low | Phase 33 | PR #59 CI run 37438346071, job "Build and test" (2026-10-06); green on re-run of the failed job | Fixed (PR #61) |
| KI-047 | The gateway's management port (8088, KI-006) also serves the gateway's routes: `:8088/api/products` is proxied like `:8080/api/products`, because the management server reuses the parent's route handlers. Same security rules, so no privilege is gained, but a caller inside the network can reach the API on a port that is meant for operations | Low | KI-006 | Found 2026-10-08 by `EdgeSecurityIT` while fixing KI-006 (a 503 from the closed test upstream). Fix idea: a `WebFilter` that answers 404 for anything but `/actuator/**` on the management port | Fixed (this PR) |
| KI-060 | The edge certificate (`k8s/tls-certificate.yaml`, KI-051) names only `localhost` and `127.0.0.1`, so the frontend team's shop, served by the same Traefik at the Ingress host `shop.localhost`, cannot be reached over HTTPS: every client refuses the certificate (web KI-034). Add `shop.localhost` to the certificate and to the Ingress `tls` hosts | Medium | KI-051 | Frontend team's request (web KI-034), relayed by the user 2026-10-10 | Fixed (PR #86) |
| KI-055 | The smoke test's rate-limit burst check (`but plenty were served first`, more than 50 of 300 served with 200) failed once on a cold CI runner (run 1 of `smoke.yml`, 2026-10-08) and passed on every run since. Root cause (from the burst counts of the 6 runs since): the check counts only `200` as "served", but a request the limiter ADMITS can still end as the catalog route's circuit-breaker fallback, `503`, when catalog-service is slower than the route's 2 s timeout on a cold runner (0 to 39 such 503s per run). Admitted requests (not 429) were 100 to 150 in every run, so the limiter never banned; the check measured catalog-service's warm-up | Low | KI-049 (the check first ran in CI) | `smoke.yml` runs 1 to 7 (burst lines: `97 x 200 200 x 429 3 x 503` ... `111 x 200 150 x 429 39 x 503`) | In progress (`fix/ki-055-burst-counts-admitted`) |

### KI-001: Swagger UI and OpenAPI docs unreachable since the split

**Found:** 2026-09-29, by the user. **Branch:** `fix/ki-001-openapi-docs`.

**What's broken:** springdoc and `OpenApiConfig` reach every service through `common`, and every
servlet service builds a spec. But:
- Each service's `SecurityConfig` blocks `/v3/api-docs` and `/swagger-ui/**`. They fall through to
  `authenticated()`, `hasAnyRole(...)` or `denyAll()`. Only `ecomdemo-app` (host port 8084) opens them.
- The gateway routes only `/api/**`, so `http://localhost:8080/swagger-ui.html`, the URL the README
  gives, returns 404.
- All six specs are titled "EcomDemo API" with server `localhost:8080`, whichever service serves them.
- Only `ecomdemo-app` has springdoc properties and a spec test. `OpenApiDocumentationTest` asserts
  that the catalogue schema is absent, and its comment ("only ecomdemo-app declares springdoc") is
  no longer true.

**Fix scope:**
- Each service with an HTTP API (catalog, customer, inventory, assistant, app) publishes its own
  `/v3/api-docs`: open the docs paths in its `SecurityConfig`, give it its own title (from
  configuration, through `OpenApiConfig`), and use the gateway as the only server. Payment and
  notification keep their docs closed or disabled.
- The gateway routes `/v3/api-docs/{service}` to each service and serves one Swagger UI (springdoc's
  WebFlux UI) with a dropdown of the service specs. "Try it out" goes through the gateway.
- A spec test per documented service. The catalogue-schema test moves to catalog-service and
  asserts `ProductWrite` and its constraints are documented. Delete the stale comment.
- README (Swagger URL and per-service docs), `security.md` API9, `decisions.md`.

**Done when:** the gateway's Swagger UI lists every documented service, each spec loads, and an
authorized "Try it out" call succeeds through the gateway. Smoke additions: the gateway's Swagger UI
answers 200, `/v3/api-docs/{service}` returns an OpenAPI 3 document per documented service, the
catalog spec contains `ProductWrite`, and payment and notification expose no docs through the gateway.

### KI-041: CORS preflight refused at the gateway

**Found:** 2026-09-30, while writing the frontend integration guide. **Branch:** `fix/ki-041-cors-preflight`.

**What's broken:** a browser sends a preflight `OPTIONS` before any cross-origin request with an
`Authorization` header or a JSON body, and it never carries a token. The gateway's security chain
(`GatewaySecurityConfig`) has no `.cors(...)`, so Spring Security handles the preflight before the
gateway's `globalcors` configuration ever sees it: `anyExchange().authenticated()` → 401, with no
`Access-Control-Allow-*` headers. Every preflight fails, `POST /api/auth/login` included. Simple
anonymous `GET`s work, which is why nothing noticed: every client so far (curl, the smoke test,
Gatling, Swagger UI on the same origin) sends no preflight. Workaround: serve the frontend from the
gateway's origin, or proxy `/api` through the frontend's dev server.

**Fix scope:**
- The gateway's security chain processes CORS first (`.cors(...)`), from the SAME configuration as
  `spring.cloud.gateway.server.webflux.globalcors` in `application.yml`, so the allowed origins,
  methods and headers stay in one place (`CORS_ALLOWED_ORIGINS`).
- A preflight from an allowed origin gets 200 with the allow headers and never reaches a service;
  one from any other origin is refused (403); an actual request carries exactly one
  `Access-Control-Allow-Origin` header.
- Regression tests in the gateway (preflight allowed, preflight from a foreign origin, no duplicate
  headers on a real request); smoke checks for the same against the running stack.
- README (the CORS paragraph), `docs/decisions.md`.

**Done when:** a preflight from `http://localhost:3000` for `POST /api/auth/login` with
`Content-Type` and for `GET /api/cart` with `Authorization` answers 200 with the allow headers;
a preflight from another origin is refused; an authenticated cross-origin `GET /api/cart` returns
200 with one `Access-Control-Allow-Origin`.

### KI-042: two HIGH CVEs in jackson-databind block the image scan

**Found:** 2026-10-01, in merge verification of PR #55 (KI-039): CI on `main` was red although the
PR changed no dependency. The same scan passed on the Phase 33 merge about ten hours earlier.
**Branch:** `fix/ki-042-jackson-cves`.

**What's broken:** Trivy (HIGH and CRITICAL fail the job, fixable or not) reports four findings in
every one of the eight images, all in application jars and none in OS packages:

| CVE | Library | Installed | Fixed in |
|---|---|---|---|
| CVE-2026-91776 `TypeDeserializerBase._findDeserializer()` | `com.fasterxml.jackson.core:jackson-databind` | 2.21.6 | 2.21.7 (also 2.18.11, 2.22.3) |
| CVE-2026-91777 forward-reference completion for `@JsonIdentityInfo` object IDs | same | 2.21.6 | same |
| CVE-2026-91776 | `tools.jackson.core:jackson-databind` | 3.1.6 | 3.1.7 (also 3.2.3) |
| CVE-2026-91777 | same | 3.1.6 | same |

`publish` needs the scan, so no image has been published to GHCR for any push to `main` since. The
library parses every request body, so this is not a theoretical dependency. OWASP Dependency-Check
passed on the same run (the NVD has not scored these at 7 or more yet); it may flag them later.

**Fix scope:**
- Raise `jackson-bom.version` 3.1.6 → 3.1.7 and `jackson-2-bom.version` 2.21.6 → 2.21.7 in the parent
  pom: Phase 31's mechanism, a patch release of the same line (Spring Boot 4.1.1 is still the newest
  release and manages the old versions). Extend the pom's comment, `docs/security.md`'s findings
  table and `docs/decisions.md`.
- Reproduce first: run CI's own scan locally (same pinned Trivy, same `scan/<module>:ci` images,
  `.trivyignore.yaml`) on the unfixed tree: 4 HIGH per image. The CI gate stays as the regression test.

**Done when:** the same local scan reports no HIGH or CRITICAL finding in all 8 images, CI's `Image scan`
and `Dependency scan` pass on the PR, and the build and smoke tests pass.

## Covered by an approved phase

| ID | Issue | Phase | Status |
|---|---|---|---|
| KI-012 | An order whose saga event is dead-lettered stays PENDING forever | Phase 32 | Fixed in Phase 32 (saga deadline) |
| KI-013 | No reconciliation of `stock_reservation` against orders | Phase 32 | Fixed in Phase 32 (close + fence) |
| KI-014 | No login throttling or lockout (BCrypt cost is the only brake) | Phase 33 | Fixed in Phase 33 (per-username and per-client throttling, 429 + Retry-After) |
| KI-015 | Symmetric JWT signing (HS256): every verifier can also mint tokens; one shared SERVICE role | Phase 33 | Fixed in Phase 33 (RS256 + JWKS, scoped client-credentials service tokens) |
| KI-061 | Kafka and PostgreSQL do not authenticate their clients in k8s: TLS proves the server to the client only (KI-058, KI-059). Any pod that can reach Kafka can produce or consume ANY topic (there is no authorization at all), and a database login needs only the password | Phase 35 | Fixed in Phase 35 (PR #89: client certificates for PostgreSQL and Kafka, per-service Kafka ACLs) |

## Candidates: new capabilities (a phase only if approved)

| ID | Capability | Source |
|---|---|---|
| KI-016 | Alertmanager: alerts fire in Prometheus and are delivered nowhere | README "Known gaps" |
| KI-017 | Refresh tokens, token revocation, "log out everywhere" | README "Known gaps" |
| KI-018 | Password change through the API | README "Known gaps" |
| KI-019 | Restore the cart when an order is cancelled | `architecture/saga.md` |
| KI-020 | Hybrid (keyword + semantic) search | README (Phase 28) |
| KI-021 | A per-user budget for AI calls | `security.md` API4 |
| KI-022 | Performance: stock check outside the checkout transaction, pipelined outbox sends, shorter poll delay, push order status (SSE), gateway cost per request and replicas, a soak test, a separate load machine | `performance.md` |
| KI-023 | Trivy config/IaC scanning of the Dockerfile and k8s manifests; Dependabot for Docker base images and compose images (Maven and GitHub Actions are already covered, `.github/dependabot.yml`) | `RECENT.md` (Phase 31) |
| KI-024 | Tempo retention and object storage; span metrics (traces to metrics) | README "Known gaps" |
| KI-048 | The SonarQube quality gate runs only locally, on demand; nothing checks it on a pull request (SonarQube Cloud with PR decoration is the usual answer) | README "Known gaps", moved here by KI-009 |
| KI-049 | CI did not run `scripts/smoke-test.sh`; the compose stack and the images were exercised only on a developer's machine. Now `.github/workflows/smoke.yml` runs it daily, on demand and on PRs labelled `run-smoke` | README "Known gaps", moved here by KI-009; **Fixed (this PR)** |
| KI-050 | Redis had no password (`requirepass`): anyone who could reach the port could read, flush or poison the cache. Now required (`REDIS_PASSWORD`, no default) in compose and k8s. The single-node / no-replica half is KI-054 | README "Known gaps", moved here by KI-009; **Fixed (this PR)** |
| KI-054 | Redis is a single node with no replica: a restart empties the cache and the rate-limit counters (acceptable for a cache; the limiter fails open) | Split out of KI-050 |
| KI-051 | No TLS at the edge: the Ingress had no `tls` section, so a Bearer token travelled in clear text. Now `https://localhost:18443` with a cert-manager certificate from a local CA; plain `localhost:18080` only redirects (301). The hops behind the edge and compose are KI-056 | README "Known gaps", moved here by KI-009; **Fixed (this PR)** |
| KI-056 | Phase A done: Traefik to the gateway, the gateway to the services and every service-to-service call (token issuance and the JWKS fetch included) are HTTPS in k8s, server-side TLS only. Open parts are KI-057..059; compose stays HTTP on `127.0.0.1` | Split out of KI-051; **Fixed (this PR)** for the services |
| KI-057 | Redis was plain on the cluster network: the password (KI-050) crossed the hop in clear text. Now TLS-only in k8s (`--port 0 --tls-port 6379`, a cert-manager certificate, `SPRING_DATA_REDIS_SSL_ENABLED` in the 4 clients, a sidecar that reloads the certificate on renewal). Compose Redis stays plain on `127.0.0.1` | Split out of KI-056; **Fixed (this PR)** |
| KI-058 | The 6 PostgreSQL databases are reached without TLS (`sslmode` is not set). Needs server certificates on Postgres and `sslmode=verify-full` in every JDBC URL. **Fixed (PR #87):** a cert-manager certificate per database, `ssl=on` and a `hostssl`-only `pg_hba.conf`, `sslmode=verify-full` with the cluster CA in the 6 clients, a `cert-reload` sidecar; k8s only, compose unchanged | Split out of KI-056 |
| KI-059 | Kafka listeners are `PLAINTEXT`. Needs a TLS listener on the broker and SSL settings in 6 services. **Fixed (PR #88):** the network listener is SSL (`kafka-tls`), the plain ones are on 127.0.0.1 only, the 5 clients (the 6th, `outbox`, is a library inside them) use SSL with the cluster CA, a `cert-reload` sidecar; k8s only, compose unchanged | Split out of KI-056 |
| KI-052 | `InventoryService.reserve`/`release` (inventory-service) have no production caller since KI-011 removed their HTTP endpoints; only tests use them (`StockChangePublisherTest`, `ConcurrentReservationTest`, `InventoryServiceTest`). The saga uses `reserveForOrder`/`releaseForOrder` | Low | KI-011 | Found 2026-10-08 while fixing KI-011, whose scope was the gateway and the endpoints. Removing them means moving those tests onto the saga methods | Fixed (this PR) |
| KI-053 | CI's image scan fails on a new HIGH CVE in `lz4-java` 1.10.1 (CVE-2026-106451, arbitrary code execution; fixed in 1.11.4), which `kafka-clients` brings into 5 images, so every PR's `Image scan` is red and `publish` is skipped | High | Phase 31 (the scanner); CVE disclosed after 2026-10-08's earlier green runs | Found 2026-10-08 on PR #78 (KI-052): `Image scan (Trivy)` failed, `Build and test` passed; Trivy `Total: 1 (HIGH: 1)` per affected image | Fixed (this PR) |
| KI-044 | Run the image scan (and the dependency scan) on a daily schedule, as `nvd-data.yml` already runs for the NVD cache, so a newly disclosed CVE shows up on its own and not on the next unrelated push (KI-042 turned a docs-and-tests merge red) | KI-042. **Fixed (PR #64)** |
| KI-045 | `mvnw.cmd` on `main` (from Dependabot PR #57) has line endings that disagree with `.gitattributes` (`*.cmd text eol=crlf`): a fresh checkout shows it modified, with a whitespace-only diff of the whole file. Renormalize it (`git add --renormalize mvnw.cmd`) in its own commit | KI-043 (seen when merging #57). **Fixed (PR #62)** |

## Accepted limits

| ID | Limit | Why it's accepted |
|---|---|---|
| KI-025 | A 403 on someone else's order admits that it exists | A conscious judgement for orders; see `OrderService.findById` |
| KI-026 | No API versioning (`/api/v1`) | One client, one version |
| KI-027 | Swagger UI has no production profile to switch it off | No production deployment; revisit with one |
| KI-028 | Most of the fast suite runs on H2 | The PostgreSQL-specific paths are covered by the integration tests |
| KI-029 | The system issues plain JWTs; no OAuth2 or OIDC flows | Out of scope for a learning project |
| KI-030 | Flyway has no undo | Community edition; roll forward with a new migration |
| KI-037 | An order whose **StockRejected** is dead-lettered is cancelled by the saga deadline with the void's reason, not the stock message | The rejection is recorded nowhere the reconciler can ask; the outcome (CANCELLED, nothing held) is right, only the wording differs (Phase 32) |
| KI-038 | The saga deadline sweep runs in every app instance, making duplicate settle/close calls | Safe: both calls are idempotent and the decision is a conditional UPDATE; the app runs one replica (Phase 32) |

## Needs check (not re-verified against the current code)

| ID | Recorded gap | Source |
|---|---|---|
| KI-031 | Nothing watches the outbox (no pending-count gauge or health indicator). `OutboxObservationConfig` may have closed this | README "Known gaps" |
| KI-032 | Cache hit rate is published but not graphed (no dashboard uses `cache_gets_total`) | README "Known gaps" |
| KI-033 | The nightly `@Scheduled` report fires in every app instance (the app runs 1 replica today) | README "Known gaps" |
| KI-034 | Restarting a batch import after the container is replaced is inferred, not tested | README "Known gaps" |
| KI-035 | The gateway writes no request log; that it logs no bodies is an absence, not a decision | `RequestLogFilterTest.java:98` |
| KI-036 | No metrics cardinality budget is asserted anywhere | README "Known gaps" |
| KI-062 | Under the smoke test's 300-request burst (40 in parallel, one caller) on a cold compose stack, 0 to 39 of the requests the rate limiter admits come back as `503` from `/fallback/catalog`: catalog-service answers `GET /api/products` slower than the route's 2 s `response-timeout` (KI-004) while it warms up. Found while root-causing KI-055 (2026-10-10). Not yet known whether it is only JIT warm-up, the connection pool, or the breaker opening | Reproduce with the burst against a fresh stack and read catalog-service's latency and the `catalog` breaker's state (`/actuator/circuitbreakers` on the gateway's management port); decide whether a warm-up or a different timeout is warranted |
