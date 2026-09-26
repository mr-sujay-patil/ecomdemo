# Phase 21 Test Report: the API Gateway

- **Date:** 2026-09-26
- **Branch:** `feature/phase-21-gateway`
- **Toolchain:** Spring Boot 4.1.1, **Spring Cloud 2025.1.3 (Gateway 5.0.3)**, Spring Modulith 1.4.1,
  PostgreSQL 18.6, Redis 8, Apache Kafka 4.2.1, Loki 3.7.8, Alloy v1.19.2, Testcontainers 2.0.5,
  JDK 21, Docker 29.8.0
- **Result:** ✅ green. `./mvnw clean verify` BUILD SUCCESS; smoke test **295 passed / 0 failed**.
- **Scope:** a single entry point. Six deployables, seventeen containers, and **one** port a client uses.

## 0. Read this first: the suite that proved the phase was written before the gateway existed

`scripts/smoke-test.sh` has always talked to one `BASE_URL`, and it has always been 8080. So the
gateway took **8080** and the application moved to **8084** — rather than the gateway taking a new port.

The consequence is the report's most useful sentence: **roughly 200 checks written in Phases 1–20 now
exercise routing, edge token validation and the rate limiter without one of them being rewritten.**
A check written alongside new code tends to agree with it. A check written a year of phases earlier
cannot.

It also decided where the *white-box* checks go. About 25 of them ask the application about itself —
`/actuator/health`, its Prometheus scrape, a heapdump, `/v3/api-docs`. Those moved to a new `APP_URL`
helper on 8084, and that mattered more than it looked: **the gateway exposes its actuator under
identical rules**, so every one of those assertions would have *passed* against the wrong process,
with "the database is one of the components" quietly false and every business meter simply absent.

## 1. Full regression

    common                10      inventory-service     34
    catalog-service       41 + 12 ITs     customer-service      43 + 8 ITs
    notification-service   8 +  3 ITs     gateway-service        7 + 15 ITs
    ecomdemo-app         218 + 53 ITs
    BUILD SUCCESS in 3:25

    scripts/smoke-test.sh  ->  295 passed, 0 failed, 0 skipped

## 2. The version question had no clean answer, so it was measured

**There is no GA Spring Cloud release train baselined on Boot 4.1.x.**

| Train | Gateway | Boot baseline | GA? |
|---|---|---|---|
| **2025.1.3** | **5.0.3** | **4.0.8** | ✅ chosen |
| 2026.0.0-M1 | 5.1.0-M1 | 4.2.0-M2 | ❌ milestone |

Three ways out: the GA train one Boot minor ahead of its baseline, a milestone (against the project's
GA-only convention), or downgrading Boot (against "latest stable 4.x", and touching every module).

The first was **tested before it was chosen**. A throwaway project on Boot 4.1.1 with the 2025.1.3 BOM
resolved to **one** Spring Framework version (7.0.9), **one** Boot version (4.1.1) and **zero**
occurrences of 4.0.8 — our parent's dependencyManagement wins over the imported BOM, as it must — and
then started and really proxied, returning `Server: cloudflare` from a live upstream while an unrouted
path got the gateway's own 404.

Residual risk: a 4.1-only internal change on a path the probe did not exercise. That is what
gateway-service's integration tests are for, and they pass.

## 3. ❗ Three ways a build can be right and a test wrong

**3.1 Optional dependencies broke four services.** The gateway is reactive, so it must not inherit
`common`'s servlet stack: Boot picks its web type from the classpath and would start Tomcat instead of
Netty, leaving Spring Cloud Gateway's server absent — an application that comes up *healthy* and routes
nothing. Marking the starters `optional` in `common` fixed that and broke everyone else:
`OpenApiConfig` is a component-scanned `@Configuration` whose bean method returns springdoc's
`OpenAPI`, and **Spring introspects a scanned class's method signatures**, so four services failed at
startup with `NoClassDefFoundError`.

The reasoning that had made `optional` look safe — "an annotation whose class is absent is silently
ignored" — is *true*, and was about the wrong one of the two. An unused annotation is safely absent; a
method signature is not.

**3.2 Exclusions are per declaration, not per artifact.** The fix was to exclude the servlet stack in
gateway-service's own pom. It worked for the main dependency — and `ecomdemo-common` is declared
*twice*, the second time as a `test-jar`, which inherited nothing. Tomcat and `spring-webmvc` arrived
at **test scope only**. The symptom was not a missing class but the opposite: Boot saw a servlet
container, built a **servlet** context, and the unconditional `@EnableWebFluxSecurity` registered
`conversionServicePostProcessor` a second time. The exception named two Spring Security classes and
nothing of ours, and **the production jar was correct throughout** — only the tests ran on the wrong
web stack.

It also hid behind the local repository: running the module alone resolved an *earlier* `common` from
`~/.m2` whose starters were still optional, so the tests **passed standalone and failed in the
reactor**. `./mvnw clean verify` over the whole reactor was the only build that told the truth.

**3.3 `@AutoConfigureWebTestClient` is for a MOCK slice.** On a `RANDOM_PORT` test it force-imports a
fixed set of auto-configurations, including the servlet security one. `WebTestClient` is now bound to
the port by hand.

## 4. ❗ Deleting the proxies removed a service token nobody was thinking about

The first cold run failed on its **first check**: `GET /api/products` through the gateway returned
**401** — from catalog-service, for a path the gateway had just permitted. The edge said yes and the
service said no.

catalog-service and inventory-service end their chains with `anyRequest().authenticated()`: a service's
API is not a public API. Browsing the catalogue *is* public, and what bridged the two was
`CatalogClient` inside the application, attaching a service token to every forwarded call. Deleting the
proxy deleted the bridge.

**No test in this repository could have caught it.** `EdgeSecurityIT` asserts what the *edge* decides
and deliberately runs no upstream services — which is what makes it fast and honest, and also blind to
exactly this. Only a cold stack with all six services could see it.

`ServiceIdentityFilter` restores it in one place, and its **order is the safety argument**: it runs
*after* the security chain, so the only requests that reach it without credentials are the ones the
edge explicitly permits anonymously. Minting before the edge had decided would hand a valid credential
to requests it was about to refuse. The order is asserted, not commented.

The mutation that matters: making the filter overwrite a caller's token fails
`aCallersTokenIsNeverReplaced`. In production that defect would not raise an error — it would produce
**one cart belonging to "gateway-service", shared by every logged-in shopper**.

## 5. ❗ A check that had become a test of its own client

The rate limit is 50 tokens a second, burst capacity 100, keyed per caller. The smoke check sent 250
requests in a sequential shell loop and **nothing was refused**. The limiter was working perfectly:
spawning a `curl` costs tens of milliseconds, so the loop managed 20–30 requests a second — below the
refill rate. Tokens were replenished as fast as they were spent.

Attempt two, `xargs -P 20`, also refused nothing, at almost exactly **50 requests a second**: twenty
workers each paying the spawn cost happen to land on the refill rate. The limiter was never the slow
part; the client was.

What works is one curl process reusing one connection across 300 requests, 40 in flight: about 150 a
second. Measured live — **171 served, 129 refused, in 2.1 seconds**.

Phase 20d had to undo three checks that were secretly asserting timings. This is the same error
arriving from the other side: a check that cannot fail because the *test* is too slow to provoke the
behaviour it names.

## 6. The security change that was the point

A proxied login put a **plaintext password through the application's memory**. `ecomdemo-app` has no
`PasswordEncoder`, no `AuthenticationManager` and no `users` table — it was carrying the credential
purely to forward it. Login is routed at the edge now, and nothing but the gateway and customer-service
ever holds it.

Three proxy surfaces went with it: `AuthProxyController`, `CustomerProxyController` and
`catalog/ProductController`, plus the empty `catalog` Modulith module and **five** authorisation rules
in the app's `SecurityConfig`. A `permitAll` left behind for a path that no longer exists is worse than
untidy — it tells the next reader this service still has an anonymous surface, when
`anyRequest().authenticated()` now genuinely covers everything.

`ProductProxyAccessTest`'s five claims were asserted in `EdgeSecurityIT` **before** the old class was
deleted, so coverage was never absent even momentarily.

Twelve test files stopped creating fixtures over HTTP and now call `CatalogGateway` — which is what
`CartService` and the batch jobs always did. That is a **better** test, not an equal one: a fixture
created through the proxy exercised the proxy on its way in, so a proxy defect could mask or
manufacture a cart failure.

## 7. Memory: six services cost less than five did

    gateway 124 · app 142 · catalog 338 · inventory 115 · notification 102 · customer 82
    kafka 188 · alloy 96 · prometheus 45 · loki 39 · grafana 37 · cache 8
    six databases 12-19 MiB each
    TOTAL 1387 MiB against 3916 — with SIX services, against 1459 with five

The gateway idles at **124 MiB of its 320 MiB cap**, and the application fell again (187 → 142 MiB) as
the proxies left it.

⚠️ **catalog-service is the tightest thing in the stack: 375.9 MiB against a 384 MiB cap (98%)**
immediately after the burst, settling to 88%. No OOM kill and no restart, but the new burst check puts
real pressure on the one service with the least headroom, and it caches. Watch it, or raise the cap.

## 8. ⚠️ Carried, not fixed

- **The catalogue is documented NOWHERE.** `ProductWrite` appeared in the app's OpenAPI only because it
  proxied `/api/products`, and catalog-service declares no springdoc. A gateway can aggregate its
  services' specifications; that is the real fix and its own piece of work. A test asserts the absence
  so that closing the gap fails loudly.
- **A correlation ID still cannot be followed across the hop.** Only `ecomdemo-app` configures
  structured logging, so the other five write the ID to the MDC and never to the line — Loki has
  nothing to filter on. The gap arrived in 20b and was invisible until a gateway-minted ID made it a
  question worth asking. The check that would have proved it is documented, in place, rather than
  asserted falsely.
- **The gateway does no request logging**, so it has no credential redaction either. `RequestLogFilter`
  is servlet code. Spring Cloud Gateway logs no bodies by default, so nothing leaks today — but the
  protection at the edge is an absence rather than a decision, and login now passes through here.
- **The Phase 19 dashboard defect** (`fix/dashboard-stat-reducers`) — still the oldest open item.
- A failed compensating release leaks a reservation · the CSV import's partial-failure window ·
  **HS256 with a shared secret** · `ecomdemo-app` is still not named `order-service`.

## 9. Environment left behind

Seventeen containers. **Gateway on 8080 — the only port a client needs.** Application on **8084** for
white-box checks (container port still 8080, so Prometheus and Alloy needed no edit). catalog 8081,
inventory 8082, customer 8083, notification 8085.

⚠️ **`.env` and `.env.example` both pinned `APP_PORT=8080`**, which overrode compose's new 8084 default
and made the gateway die with *"port is already allocated"* — a configuration mistake in an env file,
reported as a Docker networking error. Both now set 8084 and add `GATEWAY_PORT=8080`.

⚠️ **Do not run a Docker image build while the full stack is up on this machine.** Six JVMs plus a
reactor build inside BuildKit exhausted the host: three commands were killed and BuildKit's frontend
crashed with *"frontend grpc server closed unexpectedly"*. Bring the stack down, build, bring it up.
