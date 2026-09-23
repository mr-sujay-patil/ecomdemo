# Recent Phase Summaries (rolling window: last 2 phases)

> Newest first. When a third summary is added, move the oldest to `docs/progress/archive/phase-XX-summary.md`. Maximum ~30 lines per summary: facts only, no narrative.

<!-- TEMPLATE
## Phase XX: <Title> (tag: phase-XX-complete, PR #N)
**What exists now:** <1–3 lines describing the system after this phase>
**Key code:** <packages and classes that matter next>
**Config & infrastructure:** <profiles, env vars, ports, containers, and how to run>
**Tests:** <new test classes, smoke test additions>
**Gotchas:** <anything surprising the next phase must know>
**Follow-ups (not done, out of scope):** <suggestions deferred to later phases>
-->

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

## Phase 18: Reliable Event Publishing (tag: phase-18-complete, PR #23)
**What exists now:** A checkout writes its `OrderPlacedEvent` into `outbox_event` in the ORDER's
own transaction, and a scheduled relay publishes pending rows to `orders.placed` and marks them
sent. The dual-write gap Phase 17 measured is closed: with the broker stopped, the checkout still
returns 201 in under a second, the event waits in the database, and the order is notified once the
broker returns, with nobody replaying anything. Phase 17 lost 4 orders under that test; this loses
zero. A cleanup job sweeps published rows after 7 days. 392 tests (310 + 82), smoke test 270
checks. Schema **V10**. No new dependencies.
**Key code:** `com.ecomdemo.messaging` - `OutboxEvent` (two ids: a sequence for ORDER, a UUID for
IDENTITY), `OutboxWriter` (`Propagation.MANDATORY`, serialises with the CONSUMER's Jackson 2
mapper), `OutboxRelay` (the timer) + `OutboxBatchPublisher` (the transaction), `OutboxKafkaSender`
(owns its producer privately - NOT a bean), `OutboxCleanupJob`. `OrderPlacementService.placeOnce`
calls `outbox.append(...)` where Phase 17 called `events.publishEvent(...)`.
`OrderEventPublisher` and `MessagingAsyncConfig` are GONE - the outbox replaces them.
**Config & infrastructure:** `ecomdemo.outbox.poll-delay=1s`, `batch-size=100`, `retention=7d`,
`cleanup-cron=0 0 3 * * *`. No new containers, no new ports. The four properties are spelled out in
`application.properties` because `@Scheduled` resolves placeholders from the Environment, not from
the bound record.
**Tests:** `OutboxBatchPublisherTest`, `OutboxWriterTest`, `OutboxEventRepositoryTest`,
`OutboxCleanupJobTest`, `OutboxPropertiesTest`, `KafkaTemplateWiringTest` (a guard, see Gotchas),
`OutboxRelayKafkaIT` (5 IT). Smoke test section 14 stops the real Kafka container, places an order,
restarts it and waits - restored through an EXIT trap if the run is interrupted.
**Gotchas:** (1) Do NOT add a second `KafkaTemplate` bean. Boot's is
`@ConditionalOnMissingBean(KafkaTemplate.class)` so any other one deletes it, and
`DeadLetterPublishingRecoverer` resolves by TYPE - the first draft did this and stopped the
consumer's whole partition while both test suites stayed green. `KafkaTemplateWiringTest` guards
it. (2) Boot 4 is Jackson 3; spring-kafka's `JsonDeserializer` is Jackson 2. A Kafka payload must
be written by `JacksonUtils.enhancedObjectMapper()`. (3) Spring counts private constructors when
choosing one - a second "for tests" constructor gives "No default constructor found". (4) The
relay stops its batch at the first failure, on purpose.
**Follow-ups (not done, out of scope):** `SELECT ... FOR UPDATE SKIP LOCKED` so two instances do
not both publish (harmless today - the consumer dedupes - and recorded in `docs/decisions.md` as
deferred); a Micrometer gauge on the pending count and an Actuator health indicator for a stuck
outbox; CDC with Debezium as the no-application-change alternative.
