# Phase 18 Summary (archived)

> Moved out of `docs/progress/RECENT.md` when Phase 20a's summary was added. Nothing is
> deleted; it is simply no longer loaded at session start.

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
