/**
 * Dead-lettered saga events: see them, and send one back (Phase 32).
 *
 * <p>Since Phase 24 a saga message that cannot be processed at all - bytes that are not JSON, a bug -
 * is retried three times and then moved to {@code <topic>-dlt}, where nothing consumes it: "a queue
 * for a person". This module is that person's tool. It lists what is waiting in the saga's five
 * dead-letter topics, with the error that sent each record there, and replays a record to the topic
 * it came from once the cause is fixed - recording who did it, and refusing to replay the same record
 * twice.
 *
 * <p>Replay is safe because every saga consumer is idempotent ({@code processed_event}) and because
 * the saga deadline FENCES an order it has decided: a replayed OrderCreated for a closed order is
 * rejected by inventory, a replayed StockReserved for a voided order charges nothing.
 *
 * <p>A module of its own rather than part of {@code order}: the dead-letter topics belong to four
 * services' consumers, not to the order, and an operator's replay tool is infrastructure.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Dead letters",
        allowedDependencies = {"messaging", "shared"})
package com.ecomdemo.deadletter;
