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

## Phase 17: Messaging (tag: phase-17-complete, PR #21 + follow-up #22)
**What exists now:** A checkout publishes an `OrderPlacedEvent` to `orders.placed` (3 partitions,
keyed by order id) after its transaction commits, on a small async pool so the customer never waits
for the broker. A notification consumer writes one row per order, idempotently. Retries happen on
`orders.placed-retry-0/-1` with an exponential jittered backoff, then `orders.placed-dlt`, which
nothing consumes. 355 tests (278 + 77), smoke test 252 checks. Schema **V9**.
**Key code:** `messaging/OrderPlacedEvent` (a record, NOT the entity; `of()` generates the event id
that makes redelivery detectable), `messaging/OrderEventPublisher`
(`@Async` + `@TransactionalEventListener(AFTER_COMMIT)`), `messaging/MessagingAsyncConfig` (bounded
pool, ABORT policy - CallerRuns would re-introduce the blocking), `messaging/KafkaTopicsConfig`
(NewTopic beans; `REPLICAS` is the one line a real cluster changes), `messaging/ProcessedEvent`
(PK **is** the event id), `notification/NotificationService` (check + marker + work in ONE
transaction, marker first), `notification/OrderPlacedListener` (`@RetryableTopic` +
`@DltHandler`). `OrderPlacementService` publishes the domain event and knows nothing about Kafka.
**Config & infrastructure:** New dep **`spring-boot-starter-kafka`** (not the bare `spring-kafka` -
Boot 4 keeps auto-config in `spring-boot-kafka`). compose gains `kafka`
(apache/kafka:4.2.1, KRaft, two listeners: INTERNAL `kafka:9092` and HOST `localhost:29092`) and
`kafka-ui` (kafbat v1.5.0, :8090), plus `kafka-data`. ~25 `spring.kafka.*` properties. V9 adds
`notification` (UNIQUE on order_id) and `processed_event`.
**Tests:** `NotificationServiceTest` (4), `OrderPlacedKafkaIT` (4, real broker via
`KafkaContainerConfig` + `@ServiceConnection`), +1 in `OrderPlacementServiceTest`. Smoke test +18.
Test report: `docs/test-reports/phase-17.md`.
**Gotchas:** (1) `KafkaTemplate.send` BLOCKS inside the call until it has metadata
(`max.block.ms`, 60s default) - with the broker stopped a checkout took **97 seconds** before the
`@Async` fix; it is 0.18s now. (2) Retry topics are named after the DELAY by default, so jitter
creates new topic names every restart - use `SUFFIX_WITH_INDEX_VALUE`. (3) `spring-kafka` alone
gives no auto-configuration in Boot 4; the symptom is a missing KafkaTemplate BEAN. (4)
`spring.kafka.admin.auto-create=false` in the test profile, or every @SpringBootTest spends 45s
timing out. (5) Migrations must use the portable spelling (`BIGINT GENERATED BY DEFAULT AS
IDENTITY`, `TIMESTAMP(6) WITH TIME ZONE`, `CURRENT_TIMESTAMP`) - the fast suite runs on H2.
(6) After a broker restart the first notification lags tens of seconds while clients reconnect.
**The gap Phase 18 exists to close:** publishing happens AFTER the commit, so a process death in
between leaves an order with no event and nothing aware of it. Measured: 4 orders came out of the
broker outage permanently un-notified. That is the dual-write problem, and the outbox is the fix.
**Follow-ups (not done, out of scope):** the outbox (Phase 18). Nothing sweeps `processed_event`,
so it grows for ever - the index on `processed_at` is what a retention job will use. The
correlation ID does not cross the Kafka boundary, so a consumer's log lines cannot be joined to the
request that caused them - Phase 23 does that properly with trace context. No consumer-lag alert
in Prometheus. Kafka UI has no authentication.
