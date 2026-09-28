# Phase 22 Summary (archived from RECENT.md during Phase 24)

## Phase 22: Resilience (tag: phase-22-complete, PR #35)
**What exists now:** every `ecomdemo-app` → catalog-service call goes through
Retry(CircuitBreaker(Bulkhead(HTTP call with a timeout))). catalog-service down: add-to-cart is a 503
+ Retry-After in ~0.6 s, the breaker opens after 5 failed calls (refusals ~20 ms), CHECKOUT STILL
SUCCEEDS (cart snapshot), and it recovers on its own ~10 s after a restart. Grafana dashboard
"EcomDemo Resilience". Smoke **313 / 0** cold on the WSL2 workstation.
**Key code:** `ecomdemo-app/.../resilience/` (`ResilientCatalog`, `CatalogResilienceConfig` - a static
BeanPostProcessor wrapping the HTTP `CatalogClient` IN PLACE, so the ITs' @Primary fake is untouched);
`common/.../clients/catalog/CatalogProperties` (connect/read/bulk-read timeouts, two RestClients);
`common/.../shared/ServiceUnavailableException` → 503 in `GlobalExceptionHandler`.
**Config & infrastructure:** `resilience4j.*.instances.catalog.*` and `ecomdemo.catalog.*-timeout` in the
app's application.properties (250 ms connect, 500 ms read, 30 s bulk; 2 attempts; window 10 / min 5 /
50 %; open 10 s; bulkhead 20). `resilience4j.version` 2.4.0 in the parent. `scripts/failure-demo.sh`.
**Tests:** ResilientCatalogTest (loads the SHIPPED resilience4j.* properties into R4j's own
auto-config; includes the worst-case budget sum), CatalogClientTimeoutTest (real slow HttpServer),
DashboardMetricsTest (+ resilience series from the real binders); smoke §Resilience (18 checks).
**Gotchas:** a STOPPED container is not refused - cached IP → SYN unanswered (connect timeout); expired
cache → name resolution hangs ~12 s (the connect timeout does NOT cover DNS; the read timeout bounds it).
Timeouts compose: attempts × read timeout + backoff must fit the budget. `resilience4j-micrometer` must
not be test-scoped. Windows reserved 9022-9121 (WinNAT) → Prometheus 9090 failed; use PROMETHEUS_PORT.
A VM pause made one `verify` take 86 min.
**Follow-ups (not done):** cold-start poison-message partition stall (seen once in 3 cold runs); the
gateway has no breaker on its own `/api/products` route; notification-service at 97 % of its 320M cap;
resilience for the inventory calls (checkout's real dependency); Phase 21 recorded no decisions.
