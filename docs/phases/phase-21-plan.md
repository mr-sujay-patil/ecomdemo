# Phase 21 Plan: API Gateway (Spring Cloud Gateway)

- **Branch:** `feature/phase-21-gateway` · **PR title:** `Phase 21: API Gateway`
- **Scope source:** `docs/phases/phase-21-gateway.md`. Nothing here adds to that scope; the choices
  below are about *how*.

## 1. The version question, answered by measurement

**There is no GA Spring Cloud release train for Boot 4.1.x.** From Maven Central's own metadata:

| Train | Gateway | Boot baseline | GA? |
|---|---|---|---|
| **2025.1.3** | **5.0.3** | **4.0.8** | ✅ yes |
| 2026.0.0-M1 | 5.1.0-M1 | 4.2.0-M2 | ❌ milestone |

We are on Boot **4.1.1**, which falls between trains. Three ways out: use the GA train one Boot minor
ahead of its baseline, take a milestone (violates *"stable GA dependencies"*), or downgrade Boot
(violates *"latest stable Spring Boot 4.x"* and touches every module).

**I tested the first option rather than reasoning about it.** A throwaway project, Boot 4.1.1 +
`spring-cloud-dependencies:2025.1.3`:

- Dependency tree: **one** Spring Framework version (7.0.9), **one** Boot version (4.1.1), and
  **zero** occurrences of 4.0.8 anywhere. Our parent's dependencyManagement overrides the BOM, as it
  should.
- It **starts**, and it **routes**: a request to a configured route came back with
  `Server: cloudflare` from the real upstream, while an unrouted path got the gateway's own 404.

So: **Spring Cloud 2025.1.3, GA, with Boot 4.1.1.** The residual risk is that gateway 5.0.3 was
compiled against Boot 4.0.8, so a 4.1-only internal change could bite at runtime in a path the probe
did not exercise. Recorded as the phase's headline risk; the integration tests are what would find it.

## 2. Ports: the gateway takes 8080, and the app moves to 8084

`scripts/smoke-test.sh` talks to exactly **one** base URL (`BASE_URL`, line 38) and makes **zero**
direct calls to 8081–8085. So if the gateway takes over 8080, every one of the ~200 existing
client-facing checks starts flowing through it **without being rewritten** — and that is precisely the
phase's "clients only use the gateway", proven by the suite we already trust rather than by new checks
written to agree with the new code.

| | before | after |
|---|---|---|
| **gateway-service** | — | **8080** ← the only client port |
| ecomdemo-app | 8080 | **8084** |
| catalog · inventory · customer · notification | 8081 · 8082 · 8083 · 8085 | unchanged |

The ~25 **white-box** checks (`/actuator/health`, `/actuator/prometheus`, heapdump, env) must keep
hitting the **application**, not the gateway. They get a new `app_request` helper on `APP_URL`
(8084). I will make that change deliberately, endpoint by endpoint — **not** with a bulk regex. Two
of this project's worst self-inflicted wounds came from bulk edits: a macOS `sed` word-boundary
replacement that silently did nothing, and a regex that deleted a `docker stop` line.

Service ports stay published. Nothing client-facing uses them, and they are worth keeping for
debugging; the claim is proven by routing, not by hiding.

## 3. What gets built

**New module `gateway-service`** (reactive; WebFlux, not servlet — the Redis rate limiter is a
reactive filter), port 8080, 320M cap like its siblings.

**Routes.** `/api/products/**` → catalog · `/api/auth/**` + `/api/customers/**` → customer ·
`/api/cart/**`, `/api/orders/**`, batch/report paths → app · `/api/inventory/**` → inventory,
**ADMIN only**. notification-service gets **no business route**: it has no business API, and inventing
one to satisfy "routes to all services" would be fiction. Its health is reachable in-network.

**JWT validation at the edge.** The gateway validates HS256 with the same secret and **relays** the
token; every service keeps validating it too. Defence in depth, and it means no service becomes
unsafe if a route is ever misconfigured.

> ⚠️ **The error body must stay `ApiError`-shaped.** 401/403 now come from the gateway, and existing
> checks assert on the body. This is exactly the 20d defect — two services lacked the resource
> server's own `authenticationEntryPoint` and returned an empty body — arriving one layer up. Planned
> for, with a test, not discovered later.

**The two hand-written proxies are deleted.** `AuthProxyController` and `CustomerProxyController`
(`ecomdemo-app/.../identity/`), plus the `/api/products` forwarding in `catalog/ProductController`.
**This is the phase's one security win:** a proxied login currently passes a plaintext password
through the application's memory. Routing login at the edge removes the app from that path entirely.

**Redis rate limiter.** `RequestRateLimiter` + `RedisRateLimiter`, keyed by authenticated username and
falling back to client IP for anonymous traffic. Reusing the existing `ecomdemo-cache` container but on
**`database: 1`** — catalog's cache owns database 0, and a rate-limiter key namespace sharing a
keyspace with a cache that gets flushed is a defect waiting for someone to debug it.

**CORS** configured globally at the gateway.

**Correlation ID — not a new concept.** `common/.../logging/CorrelationId` already defines
`X-Correlation-Id` / MDC `correlation_id`, and `CorrelationIdFilter` implements it as a **servlet**
filter, which does nothing in a reactive gateway. So: a WebFlux `WebFilter` reusing the same
constants, generating the ID at the **true** edge and relaying it, with the services' existing filter
continuing to honour an inbound header. The value of the ID is that it is the *same* one everywhere;
a second spelling at the boundary would defeat the whole point.

## 4. Tests

- **Route configuration** — a test that every declared route's URI resolves to a known service, so a
  typo'd host fails the build instead of 502'ing at runtime.
- **`GatewayRoutingIT`** — Testcontainers Redis; the rate limiter returns **429** past its burst, and
  the body is an `ApiError`.
- **Security at the edge** — anonymous product reads pass, writes need ADMIN, a tampered token yields
  `ApiError`, and the token reaches the downstream service.
- **Correlation ID** — an inbound ID is preserved end to end; an absent one is generated at the edge.
- **The three guards must be extended, or they lie by omission:**
  `DockerfileCoversEveryModuleTest`, `EveryModuleWithIntegrationTestsRunsThemTest` (gateway-service
  **must name Failsafe** — see `docs/test-reports/phase-20d.md` §0), and the Alloy log-coverage test.

## 5. Smoke additions

`BASE_URL` → the gateway, unchanged at 8080. New `APP_URL` for white-box checks. New: a burst returns
**429** and recovers; the correlation ID survives the hop; the old proxy paths still work *through the
gateway* (same URLs, different owner — clients cannot tell, which is the point).

## 6. Risks, stated up front

1. **Gateway 5.0.3 on Boot 4.1.1** — GA but one minor ahead of its baseline. Probed, not proven.
2. **A sixth JVM.** 1459 MiB of 3916 today; a gateway idles ~150 MiB. Comfortable, and measured at the
   end rather than projected — 20a's projection overshot by ~2×.
3. **Consumer readiness ≠ container health** still applies; the smoke suite's probe order stands.
4. **Two proxies deleted in the same PR that adds their replacement.** If the gateway's route is
   wrong, the endpoint is simply gone. The ITs cover each replaced path before the deletion lands.

## 7. Not doing (suggest, don't build)

Service discovery / Eureka · client-side load balancing · circuit breakers (Phase 22) · request
tracing (Phase 23) · TLS termination · moving authorisation rules out of the services · renaming
`ecomdemo-app` to `order-service` · the Phase 19 dashboard fix.
