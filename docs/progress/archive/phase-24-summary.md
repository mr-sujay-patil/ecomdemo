# Phase 24 Summary (archived from RECENT.md during Phase 27)

## Phase 24: Distributed Transactions (tag: phase-24-complete, PR #40)
**What exists now:** checkout is a CHOREOGRAPHED SAGA over Kafka. `POST /api/orders` = stock pre-check
(read, instant 409) + order PENDING + `OrderCreatedEvent` in the outbox -> 201 PENDING. inventory
reserves all lines or none (`inventory.stock-reserved` / `-rejected`), the MOCK payment-service
(8086, payment-db 5437) charges or declines above `PAYMENT_DECLINE_ABOVE`=10000.00
(`payments.completed` / `-failed`), the app moves PENDING -> CONFIRMED / CANCELLED; on a decline
inventory RELEASES the stock (compensation). `GET /api/orders/{id}/status`. 20 containers.
Smoke **361 / 0 / 0** on three cold runs of the final code, WSL2 workstation.
**Key code:** new reactor module `outbox` (`Outbox`, `OutboxRoutes`, `ProcessedEvents`,
`SagaListenerErrors`, `@EnableOutbox`); app `order/internal/saga/OrderSagaHandler` +
`OrderRepository.transition` (conditional UPDATE = semantic lock); inventory `saga/InventorySagaHandler`
+ `InventoryService.reserveForOrder/releaseForOrder` + `stock_reservation`; `payment-service` module.
**Config & infrastructure:** app V18 (`processed_event`), V19 (PENDING/CONFIRMED/CANCELLED, PLACED rows
-> CONFIRMED); inventory V3; payment V1. Each publisher declares its topics (3 partitions) + `-dlt` (1).
Consumer groups `order-service`, `inventory-service`, `payment-service`. Design + orchestration
alternative: `docs/architecture/saga.md`.
**Tests:** outbox module 39 unit; InventorySagaTest (9, Postgres), PaymentServiceTest (5, Postgres),
OrderSagaHandlerTest (5); app ITs answered by `FakeSagaParticipants` over the real Kafka container
(OrderApiIT decline path). Smoke §Saga (25 checks): CONFIRMED, CANCELLED + RELEASED + stock
restored + no notification, two buyers one unit. `./mvnw clean verify` 524 tests.
**Gotchas:** never edit `scripts/smoke-test.sh` while it runs (bash reads it as it goes; run a copy).
A spy of a `@Transactional(MANDATORY)` bean must be stubbed on `AopTestUtils.getUltimateTargetObject`.
`@AutoConfigurationPackage` for a package the app already covers duplicates every repository.
Spring Batch runs the sales report once per date - one IT per day. OrderPlaced (the thank-you) is now
published on CONFIRMATION, so notification tests wait for the whole saga.
**Follow-ups (not done):** a saga timeout (PENDING after a dead-lettered event is forever) and
reservation reconciliation; restore the cart on cancel; notification-service onto `ProcessedEvents`;
prune `processed_event`; remove the now-unused inventory reserve/release HTTP API.
