## Phase 19: Modular Monolith (tag: phase-19-complete, PR #24 + follow-up #25)
**What exists now:** Fourteen named modules, each declaring in `package-info.java` exactly which
others it may depend on, enforced by `ModularityTest` - an undeclared import now fails the build
naming both ends. The graph is ACYCLIC. Every module keeps its published API at its package root
and everything else under `<module>.internal`. No schema change, no new endpoint, no new container,
and the smoke test is **270 checks, unchanged**, which is the phase's real result: ~90 files moved
and no behaviour did. 401 tests (319 + 82). Schema still **V10**.
**Key code:** `catalog` (was `product`) and a new `inventory` that owns stock movement;
`shared` (was `common`); `customer.CurrentUser` (was in `security`) and `shared.TokenClaims` (was
`JwtConfig.Claims`) - those two moves broke the application's only dependency cycle. Three new
narrow APIs replaced cross-module repository access: `customer.UserDirectory`,
`catalog.ProductService.findFirstByName/saveAll`, `messaging.EventDeduplicator.claim`.
**Config & infrastructure:** `spring-modulith-api` at COMPILE scope (annotations go on main
source), `spring-modulith-core` and `-docs` at TEST scope. Boot 4.1.1 does not manage Spring
Modulith and the GA line targets Boot 3.5 - keeping the runtime starter out is what makes that gap
safe. Nothing else changed.
**Tests:** `ModularityTest` (boundaries + regenerates `docs/modules/`), `StockMutationRulesTest`
(ArchUnit: who may write stock), `EventDeduplicatorTest` (the idempotency assertions, moved from
`NotificationServiceTest`). 23 test classes moved into `internal` test packages to follow the
classes they cover.
**Gotchas:** (1) `catalog` and `inventory` SHARE the `product` table and the `Product` entity - the
split is behavioural, so the ArchUnit rule permits BOTH modules to write stock; `ProductService.update`
is the admin's full replace and cannot call into inventory without a cycle. (2) `ModularityTest`
writes into `docs/modules/` on every run, so `./mvnw test` touches the working tree - deliberate,
so a stale diagram shows as an uncommitted change. (3) Moving a package-private class into
`internal` breaks its test unless the test moves too. (4) `Documenter` drops `logging` from the
overall diagram because it has no edges; cosmetic.
**Follow-ups (not done, out of scope):** split `product` and `product_stock` so the stock rule can
name one module (Phase 20, if the service split demands it); let `catalog` contribute its own cache
configuration so `cache` stops knowing products exist; `order -> cart` stays a direct call on
purpose, since `clearCart` is inside the checkout transaction.
