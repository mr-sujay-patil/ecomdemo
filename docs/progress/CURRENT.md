# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-27
- **Phase:** 22: Resilience (Resilience4j)
- **Branch:** feature/phase-22-resilience
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Phase 21 merge verification — `phase-21-complete` IS TAGGED (at `f7d5021`)
Verified on the Mac 2026-09-26 with the stack down: `verify` BUILD SUCCESS, smoke **295 / 0**. Recorded in
the annotated tag. Phase 22 starts on the WSL2 workstation (24 CPU, 30 GB); `gh` installed + authenticated
and `.env` created there on 2026-09-27. A baseline `verify` runs on this machine before any change.

## Checklist (from `docs/phases/phase-22-resilience.md`)
- [ ] Circuit breaker, retry and timeout on order → catalog calls, with fallbacks (a clear 503)
- [ ] A bulkhead
- [ ] Resilience metrics in Grafana
- [ ] A failure demo
- [ ] Smoke: stop catalog-service → fails fast (< 2 s) with 503; restart → recovers

## Design decisions (made at PLANNING, record in `docs/decisions.md`)
- **Checkout does NOT call catalog-service** (since 20c it charges the cart's snapshot price and only
  talks to inventory). The shopper's order → catalog call is **add-to-cart** (`CartService.addItem →
  requireProduct`), plus the CSV import. So the smoke check is: catalog down → `POST /api/cart/items`
  is a 503 in < 2 s, AND checkout of an already-filled cart still SUCCEEDS. Making checkout call
  catalog just to fail would add coupling to satisfy a test. Flag this in the PR.
- **Library:** `resilience4j-spring-boot4` 2.4.0 (GA, built on Boot 4.0 / Spring 7). Spring Cloud
  CircuitBreaker has no retry, so it covers less of the checklist.
- **Where:** a `@Primary` decorator of `CatalogGateway` in `ecomdemo-app`, decorating PROGRAMMATICALLY
  (registries from properties) so the order Retry(CircuitBreaker(Bulkhead(call))) is visible in code.
  Resilience is the caller's policy; `common` keeps the plain client.
- **Timeout = the HTTP client's connect/read timeout** (catalog properties in `common`), not a
  TimeLimiter — a TimeLimiter on a blocking call abandons the thread, it does not stop it.
- **Retry only reads** (`requireProduct`, `findAll`); writes get CB + bulkhead only. Retry never
  retries `CallNotPermitted`/`BulkheadFull` (that is a retry storm). 404/4xx are not failures.
- Circuit state is NOT a health indicator: an open breaker must not take the app out of readiness.
- 503 carries `Retry-After`, mapped in `GlobalExceptionHandler` via a new `ServiceUnavailableException`.

## Next action
Check the baseline `verify` result, commit the housekeeping (`docs(progress): start phase 22`), then
implement the checklist in order.

## ⚠️ Carried, not fixed (oldest first)
- **Phase 19 dashboard defect** — two `EcomDemo Overview` stat panels reduce an instantaneous rate with
  `lastNotNull`. Branch `fix/dashboard-stat-reducers`.
- A failed compensating release leaks a reservation; nothing reconciles it.
- The CSV import is a distributed write with no shared transaction (restartable, idempotent).
- **HS256 with a shared secret** — every service can mint as well as verify. Wants asymmetric keys + JWKS.
- `ecomdemo-app` is not yet named `order-service`.
- catalog-service has no springdoc; a correlation ID dies at the hop; the gateway does no request logging.
