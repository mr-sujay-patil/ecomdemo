## Phase 06: Transactions & Concurrency (tag: phase-06-complete, PR #6)
**What exists now:** Checkout is one database transaction, and the last unit of a product can be
sold exactly once. A failure anywhere in a checkout leaves the catalogue, the cart and the order
history as they were; two simultaneous checkouts for the same stock end as one 201 and one 409,
never two orders. Every attempt, successful or refused, is recorded in `order_audit`.
**Key code:** `OrderPlacementService.placeOnce()` (`@Transactional`, the whole unit of work) and
`OrderService.place()` (no transaction, three attempts, then `ConcurrentUpdateException` -> 409)
are SEPARATE beans, so the retry crosses the proxy. `OrderAuditService.record(...)` is
`Propagation.REQUIRES_NEW`. `Product.version` is `@Version` with no getter. `ProductService` is
class-level `readOnly = true` with four writers overriding it; `CartService` is read-write
throughout. `ConcurrentUpdateException extends ConflictException`; `GlobalExceptionHandler` also
maps a raw `OptimisticLockingFailureException` to 409.
**Config & infrastructure:** No new dependencies and no new properties. Migration
`V4__add_product_version_and_order_audit.sql` adds `product.version BIGINT NOT NULL DEFAULT 0`
and the `order_audit` table (no foreign key, `order_id` nullable, `idx_order_audit_recorded_at`).
It applied incrementally to the live Phase 5 database: "Successfully applied 1 migration ... now
at version v4".
**Tests:** 120 total, was 108. `ConcurrentCheckoutTest` (`@SpringBootTest`, real threads and a
CountDownLatch) adds 4; `OrderPlacementServiceTest` holds the 6 place-order unit tests moved out
of `OrderServiceTest` plus 3 on auditing; `OrderServiceTest` is rewritten around the retry budget
(8, was 10); `FlywayMigrationTest` adds 1. The smoke test grows from 65 checks to 78.
**Gotchas:** `@Transactional` does nothing when a method is called from its own class — that is
why the retry and the unit of work are two beans, and why `CartService.view()` cannot be
`readOnly` (it calls `currentCart()`, which creates the cart on first use, and manual flush mode
would silently drop that insert). A `readOnly` transaction that JOINS a read-write one does not
make it read-only: the outer transaction's settings win, which is what lets
`ProductService.requireProduct` hand back a product the checkout then modifies. Every
`@SpringBootTest` shares one H2 database, so a test that creates rows pollutes the next class —
`FlywayMigrationTest` now counts V2's ten seeded names instead of the whole table, and
`ConcurrentCheckoutTest` deletes its own products in `@AfterEach`. In the smoke test two
backgrounded `curl`s do not reliably race; `curl --parallel --parallel-immediate` does.
**Follow-ups (not done, out of scope):** running the race against real PostgreSQL in the build
rather than by hand — Phase 7 (Testcontainers). An endpoint over `order_audit`, a
`CHECK (stock_quantity >= 0)`, and backoff between retries — not planned. Per-user carts remove
the "two checkouts of one shared cart" oddity — Phase 8.
