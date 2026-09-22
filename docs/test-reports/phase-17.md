# Phase 17 Test Report: Messaging

- **Date:** 2026-09-22
- **Branch:** `feature/phase-17-kafka`
- **Toolchain:** Spring Boot 4.1.1, `spring-boot-starter-kafka` (spring-kafka 4.1.1, kafka-clients
  4.2.1), Apache Kafka 4.2.1 in KRaft mode, kafbat Kafka UI v1.5.0, PostgreSQL 18.6, Redis 8,
  Loki 3.7.8, Alloy v1.19.2, Testcontainers 2.0.5, JDK 21, Docker 29.7.2, Compose v5.4.0
- **Result:** ✅ green. `./mvnw clean verify` 278 + 77, 0 failures, 0 skipped; smoke test 252
  passed, 0 failed, 0 skipped. **Two defects were found by this phase's own testing and fixed**
  (§5 and §6), and one gap is left open deliberately because closing it is Phase 18 (§7).

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 278, Failures: 0, Errors: 0, Skipped: 0     (surefire, was 273)
Tests run: 77,  Failures: 0, Errors: 0, Skipped: 0     (failsafe, was 73)
BUILD SUCCESS  (1:55 min)
```

| Class | Suite | What it covers |
|---|---|---|
| `NotificationServiceTest` | unit (+4) | the idempotency rule: a duplicate does nothing, the marker carries the id **from the message**, and the marker is written before the work |
| `OrderPlacementServiceTest` | unit (+1) | a placed order is announced for publication, with an event id |
| `OrderPlacedKafkaIT` | integration (+4) | checkout → topic → notification, redelivery, a rejected checkout publishing nothing, and a poison message not blocking the partition |
| `FlywayMigrationTest`, `ProductApiIT` | extended | V9 in the expected history; and `ProductApiIT.updateThenDelete` now asserts the DELETE's own status (see §6) |

`./mvnw clean test` → **278 tests, 0 "Creating container" lines, 29s**. The fast suite is still
Docker-free: `spring.kafka.listener.auto-startup=false` and `spring.kafka.admin.auto-create=false`
in the test profile.

## 2. Every "Done when" item

| Item | How it was verified |
|---|---|
| Kafka (KRaft) and Kafka UI in Compose | ✅ both run; the broker's health check asks it to describe its own cluster, which fails until it is genuinely serving |
| `OrderPlacedEvent` on `orders.placed`, keyed by order id | ✅ smoke test reads the message back off the topic **with its key** and matches the order id |
| A `notification` consumer writing a log line and a row | ✅ 1 unit class + IT + 4 smoke checks |
| Retry topics with backoff and a DLT | ✅ `orders.placed-retry-0`, `-retry-1`, `-dlt` asserted by name in the smoke test |
| An idempotent consumer backed by `processed_event` | ✅ the same event sent twice writes one notification — asserted in the IT *and* in the smoke test against the live stack |
| A Testcontainers Kafka test | ✅ `OrderPlacedKafkaIT`, real broker, `apache/kafka:4.2.1` — the same version as compose |
| **Each order produces exactly one notification, and poison messages land in the DLT** | ✅ both halves asserted separately; the DLT check watches the topic's end offsets grow |

## 3. Smoke test — `scripts/smoke-test.sh`

```
Summary: 252 passed, 0 failed, 0 skipped      (was 234)
```

```
Messaging
  PASS  the orders.placed topic exists
  PASS  and it has 3 partitions, the ceiling on consumer parallelism
  PASS  the retry topics exist, named by index
  PASS  and so does the dead-letter topic
  PASS  an order is placed for the messaging check
  PASS  the topic grew by exactly one message
  PASS  exactly one notification row exists for the order
  PASS  and it is addressed to the customer who placed it
  PASS  the event was recorded as processed, which is what makes a redelivery a no-op
  PASS  the order's event is on the topic
  PASS  and it is keyed by the order id, which is what fixes its partition
  PASS  a poison message ends on the dead-letter topic
  PASS  an order placed AFTER the poison message still succeeds
  PASS  and it is still notified, so the poison never blocked the partition
  PASS  the same event delivered twice writes ONE notification
  PASS  the notification consumer group has caught up (lag 0)
```

Plus two in the Flyway section for V9's constraints.

## 4. The application, run as it runs at this phase

`docker compose up -d --build` → nine containers, all healthy. Topics after startup, created by
the `NewTopic` beans rather than by a producer's first send (`auto.create.topics.enable=false`):

```
__consumer_offsets
orders.placed
orders.placed-dlt
orders.placed-retry-0
orders.placed-retry-1
```

## 5. ❗ Defect found by the failure scenario: a 97-second checkout

The protocol asks for a failure scenario where the phase is about reliability. Stopping the broker
found a real bug in this phase's own code:

```
docker stop ecomdemo-kafka
checkout with Kafka DOWN: HTTP 201 in 97.77s
```

**Why.** `KafkaTemplate.send` returns a future and therefore *looks* asynchronous, but it blocks
inside the call until the producer has cluster metadata — `max.block.ms`, **sixty seconds** by
default. And `@TransactionalEventListener(AFTER_COMMIT)` runs on the thread that committed the
transaction, which is the request thread. So a broker outage became checkout latency: exactly the
coupling the asynchronous design was meant to remove.

**Fix**, both halves needed:

- `@Async` onto a small bounded pool (`MessagingAsyncConfig`), so nobody waits for the send. The
  pool uses an **abort** policy rather than `CallerRunsPolicy` — the usual advice, and exactly
  wrong here, because it hands the work back to the request thread.
- `max.block.ms=5000`, so a pool thread is never parked for a minute on a broker that is not there.

**After:**

```
checkout 1 with Kafka DOWN: HTTP 201 in 0.18s
checkout 2 with Kafka DOWN: HTTP 201 in 0.05s
```

**On recovery**, `docker start ecomdemo-kafka` and the next order is notified normally — but not
instantly: the first notification after a restart lagged by tens of seconds while the consumer
rediscovered the broker. Worth knowing before concluding that something is broken.

## 6. A second defect, and a flake made legible

**Retry topics were being created with unstable names.** The first run produced
`orders.placed-retry-1031` and `orders.placed-retry-1661`: Spring names retry topics after the
backoff *delay* by default, and `@BackOff(jitter = 250)` makes that a different number on every
start — two new orphan topics per restart, for ever. Fixed with
`TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE`, giving `-retry-0` and `-retry-1`, and asserted by
name in the smoke test.

**`ProductApiIT.updateThenDelete` failed once**, during a run where Docker was heavily loaded (the
isolated re-run took 860 seconds for seven tests). It surfaced as
`Cannot map null into type int` — Jackson failing to read a `ProductResponse` as an `ApiError` —
because `TestRestTemplate.delete()` returns void and swallows the DELETE's status entirely, so a
delete that did not happen was reported one line later as a parse error. The test now asserts the
DELETE returns 204 before asserting the 404. It has passed in every run since; if it recurs, it
will name its own cause instead of the JSON parser's.

## 7. The gap left open on purpose

The event is published **after** the commit, which leaves the dual-write window: process dies
between commit and send, and the order exists with nothing published for it. Measured rather than
assumed — after the outage in §5:

```sql
SELECT count(*) FROM orders o
WHERE NOT EXISTS (SELECT 1 FROM notification n WHERE n.order_id = o.id);
-- 4 orders, permanently un-notified
```

No retry can fix this, because nothing knows the message is missing. Writing the event to the same
database in the same transaction and relaying it afterwards is the transactional outbox — **Phase
18**. Doing it the simple way first is deliberate: the outbox is hard to appreciate until you can
point at exactly what it buys.

## 8. ⚠️ Still outstanding from Phases 15–16

The Grafana dashboards' **render** has still never been looked at by human eyes — Chrome's site
permissions block `localhost:3000` for browser automation in this environment. All the data behind
them is verified. Steps in `docs/test-reports/phase-15.md` §8.

Kafka UI (<http://localhost:8090>) is in the same position: it is not required by anything and no
check depends on it, but nobody has looked at it. It is the nicest way to see a partition and a
key, so it is worth a minute.

## 9. Environment left behind

Docker Desktop running. Nine containers up (`app`, `db`, `cache`, `prometheus`, `grafana`, `loki`,
`alloy`, `kafka`, `kafka-ui`), schema **V9**. The SonarQube stack was stopped during this phase to
free memory — `docker compose -f compose.sonar.yaml up -d` brings it back. `.env` holds a real
`JWT_SECRET` and is gitignored. No stray Java processes.
