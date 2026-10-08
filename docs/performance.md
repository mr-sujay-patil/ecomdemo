# Performance Testing (Phase 30)

How EcomDemo behaves under load, what limited it, and what was changed. Every number here was
measured with the Gatling simulations in `performance-tests/`, run through `scripts/perf-test.sh`.

## How to run

    docker compose up -d                                   # the stack, as usual
    scripts/perf-test.sh browse steady                     # 20 sessions/s for 60 s
    PERF_RATE=50 scripts/perf-test.sh checkout ramp        # climb 1 -> 50 sessions/s
    PERF_LABEL=my-run scripts/perf-test.sh mixed spike     # label the results line

Knobs are `PERF_*` environment variables (`PerfConfig.java`). Each run prints a table, appends it to
`performance-tests/target/perf-results.tsv`, and leaves the HTML report under
`performance-tests/target/gatling/`.

## Method

| | |
|---|---|
| Target | the gateway (`:8080`), like a real client. Everything goes through JWT checks and the rate limiter |
| Users | 200 pre-registered customers. Tokens are fetched before the clock starts, so login (BCrypt) is not inside every session |
| Data | 20 `Perf Product N` items, stock reset to 1 000 000 on every run, prices below the mock payment's decline limit |
| Model | **open**: sessions *arrive* at a rate, whatever the earlier ones are doing (no coordinated omission) |
| Profiles | `ramp` 1 -> RATE over DURATION; `steady` 30 s climb, then RATE for DURATION; `spike` RATE, then SPIKE_USERS within 10 s, then RATE |
| Journeys | **browse**: list, then 3 product pages, 200-800 ms think time. **checkout**: 1-2 cart adds, `POST /api/orders`, poll the status every 250 ms until the saga settles. **mixed**: 80 % browse, 20 % checkout |
| Machine | one WSL2 host, 24 cores, 30 GB, running the whole compose stack **and** Gatling |

**Read the numbers as relative.** The load generator shares the host's CPU with the system under
test, so absolute limits are this laptop's. What carries over is the *comparison*: the same load,
one thing changed.

**Time to settle.** `POST /api/orders` answers `201` as soon as the order is saved PENDING. Stock
and payment happen afterwards, in the saga. So checkout also reports **order settled**: the time
from the 201 to the first poll that sees `CONFIRMED`. Its resolution is the 250 ms poll interval.

Semantic search is left out on purpose. Each search embeds the query with the model server (Ollama,
on the GPU), so loading it would measure Ollama.

## Results

### Browse is cheap

| Run | Requests | req/s | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|---|
| browse ramp 1 -> 200 sessions/s, 120 s | 48 240 | 392 | 2 ms | 4 ms | 6 ms | 0 |

Every read is a Redis cache hit, and the busiest container was the gateway at well under one core.
Browse did not find a limit on this machine.

### Checkout has a knee

A checkout ramp from 1 to 100 sessions/s kept the HTTP calls fast (`POST /api/orders` p95 621 ms)
but the **saga fell behind** at about 36 orders/s:

| checkout ramp 1 -> 100/s, 120 s | |
|---|---|
| orders placed | 6 060 |
| order settled p50 / p95 / p99 | 18.6 s / 46.3 s / 49.6 s |
| not CONFIRMED within 30 s | 594 (10 %) |

At 3 orders/s the same journey settles in about 1.5 s. So it's a queue, not slow work.

## Bottleneck: the outbox relay (found, fixed)

### Finding it

Every saga hop goes through a transactional outbox: the event is written in the business
transaction and a scheduled relay publishes it to Kafka later. The outbox tables show where
events waited during the ramp (`published_at - created_at`):

| Outbox | Events | Avg wait | Max wait |
|---|---|---|---|
| app (`db`) | 12 484 | **23.5 s** | **55.1 s** |
| inventory-service | 6 242 | 0.73 s | 1.46 s |
| payment-service | 6 242 | 0.85 s | 1.13 s |

The app writes two events per order (`OrderCreatedEvent` and `OrderPlacedEvent`), and its relay
published them in batches of exactly 100, about one batch a second. Each batch took **~217 ms**
because every send waits for Kafka's acknowledgement. After that the relay slept for its
`poll-delay` of **1 s**, however much was waiting:

    100 events / (0.217 s + 1 s) = ~82 events/s = ~41 orders/s

That matches the knee. The relay's own javadoc said "about a hundred events a second". The sleep
after a full batch was the real ceiling, not Kafka and not the database.

### Baseline before the fix

`PERF_RATE=50 PERF_DURATION_SECONDS=60 scripts/perf-test.sh checkout steady`, 50 orders/s, just
above the ceiling:

| | before |
|---|---|
| orders | 3 765 (0 cancelled) |
| order settled p50 / p95 / p99 | 13.0 s / 25.9 s / 26.7 s |
| app outbox wait avg / max | 12.5 s / 25.6 s |
| `POST /api/orders` p95 | 29 ms |
| status poll p50 / p99 | 6 ms / **21.8 s** |
| total load | 1 408 req/s, of which **1 341 were status polls** |

The last two rows are a second lesson. A slow saga makes every client poll longer, so the front
door gets busier: 95 % of all requests were "is it done yet?". The app answered those polls with a
p99 of 33 ms (its own metrics), and the gateway recorded at most 2.35 s for a completed request. The
21.8 s tail was queueing before the gateway's timer started: the gateway was using ~3 cores and
shared the host with Gatling. A backend slowdown turned into an edge overload.

### The fix

`OutboxRelay` keeps publishing while batches come back **full** (a full batch means more is waiting)
and sleeps only after a short one. It also sleeps after `ecomdemo.outbox.max-batches-per-tick`
batches (default 20), so it never holds the single scheduler thread it shares with the cleanup job
and the sales report for more than a few seconds.

What did not change:

- An idle outbox still makes one query per `poll-delay`.
- Each batch is still its own transaction.
- A failed send still stops the batch and leaves the rest pending.
- Sends are still one at a time, each acknowledged before the next.

Covered by `OutboxRelayTest`.

### After the fix

Same command, with app, catalog, inventory and payment rebuilt (every service with an outbox):

| checkout steady 50/s | before | after |
|---|---|---|
| orders (not confirmed) | 3 765 (0) | 3 765 (0) |
| order settled p50 / p95 / p99 | 13.0 s / 25.9 s / 26.7 s | **2.5 s / 3.0 s / 3.3 s** |
| app outbox wait avg / max | 12.5 s / 25.6 s | **0.98 s / 1.64 s** |
| status polls per second | 1 341 | 400 |
| status poll p99 | 21.8 s | **9 ms** |
| all requests p99 | 39.5 s | **19 ms** |

The ramp to 100 orders/s that found the knee, run again:

| checkout ramp 1 -> 100/s | before | after |
|---|---|---|
| order settled p50 / p95 / p99 | 18.6 s / 46.3 s / 49.6 s | **3.3 s / 5.4 s / 5.9 s** |
| not CONFIRMED within 30 s | 594 | **0** |
| app outbox wait avg / max | 23.5 s / 55.1 s | **1.29 s / 3.27 s** |

No new knee up to 100 orders/s. The busiest containers at the top were the gateway (~2.1 cores)
and the app (~1.8), so the next limit is CPU on this machine rather than a queue in the design.

Fixing the saga also fixed the edge. The number of polls fell by more than two thirds and the
gateway's 21.8 s tail went away, without touching the gateway.

## Comparisons

`scripts/perf-compare.sh <cache|app-pool>` recreates one service per setting (compose reads
`CATALOG_CACHE_TYPE`, `APP_DB_POOL_SIZE` and `CATALOG_DB_POOL_SIZE`, all defaulting to today's
values), runs the same simulation, restores the defaults and prints the runs side by side. All raw
lines are in `docs/test-reports/phase-30-perf-results.tsv`.

### Cache on / off (catalog-service, browse)

| browse | cache | req/s | p50 | p95 | p99 | max | errors |
|---|---|---|---|---|---|---|---|
| steady 200 sessions/s | Redis | 646 | 2 ms | 5 ms | 9 ms | 118 ms | 0 |
| steady 200 sessions/s | **off** | 646 | 3 ms | 7 ms | **38 ms** | **1 038 ms** | 0 |
| ramp 1 -> 600 sessions/s | Redis | 1 173 | 2 ms | 42 ms | 173 ms | 1 052 ms | 0 |
| ramp 1 -> 600 sessions/s | **off** | 1 173 | 4 ms | 72 ms | 161 ms | 1 025 ms | 0 |

Latency alone makes the cache look almost optional. The catalogue has about 40 products, and
PostgreSQL answers a 40-row query about as fast as Redis returns a cached one. At the top of the
ramp both runs slowed down together, because the **gateway** (~3.3 cores: JWT checks plus a Redis
rate-limit call per request) was the limit, not the catalogue.

The cache's real effect is **work that doesn't happen**. CPU at the top of the ramp:

| container | cache on | cache off |
|---|---|---|
| catalog-service | 167 % | 229 % |
| inventory-service | 0.3 % | **148 %** |
| inventory-db | ~0 % | **34 %** |
| catalog-db | ~1 % | **37 %** |

Without the cache, every product page is a catalogue query **plus an HTTP call to
inventory-service** for the stock level, plus that service's own query. That's about 2.5 cores
of backend and database work the cache removes at 1 200 req/s. The work would grow with the
catalogue, while the cache hit stays one Redis `GET`.

### Connection pool sizes (ecomdemo-app, checkout steady 80/s)

| Hikari `maximum-pool-size` | orders | failed | order settled p50 / p95 | `POST /api/orders` p95 |
|---|---|---|---|---|
| 2 | 6 015 | **3 339** | 369 s / 392 s | 60 s (timeouts) |
| 5 | 6 015 | 0 | 3.6 s / 4.7 s | 23 ms |
| **10** (today) | 6 015 | 0 | 3.6 s / 4.7 s | 17 ms |
| 30 | 6 015 | 0 | 3.4 s / 4.4 s | 17 ms |

- **Bigger is not better.** 5, 10 and 30 are within noise of each other. Little's law explains
  why: connections needed = arrival rate x time each one is held. At 80 checkouts/s with
  transactions of a few milliseconds, that's a handful. 30 idle connections buy nothing and cost
  PostgreSQL memory per connection. Multiply that by instances and services, and it adds up.
- **Too small collapses; it doesn't slow down gently.** With 2 connections, requests queued for
  one until Hikari's 10 s `connection-timeout` (the 500s), Tomcat's 200 threads all ended up
  waiting, and the rest of the requests waited behind them until the client gave up (60 s). The
  saga slowed down too: the outbox relay and the Kafka listeners borrow from the same pool.
- **Why checkout holds a connection longer than it needs to.** `OrderPlacementService.placeOnce()`
  is `@Transactional` and calls inventory-service over HTTP inside the transaction, so each
  checkout holds a connection for the length of a remote call. That's the lever to pull before
  the pool size (follow-up below).

Kept at 10: it has headroom at twice the load that used to break the saga.

## Spike, and what overload looks like

`PERF_RATE=100 PERF_SPIKE_USERS=3000 scripts/perf-test.sh mixed spike`: 100 sessions/s (80
browse, 20 checkout), then 3 000 sessions within 10 s (2 400 browse, 600 checkout), then back to
100/s.

| | result |
|---|---|
| requests | 66 956, **0 failed** |
| p50 / p95 / p99 | 2 ms / 59 ms / 113 ms |
| orders (not confirmed) | 2 400 (0) |
| order settled p50 / p95 | 2.3 s / 4.1 s |

It absorbed the spike and recovered. No request failed, and every order confirmed within 5 s.

The first attempt at this run was accidentally a stress test. A bug in the simulation gave the
checkout scenario the whole spike (3 000 checkouts in 10 s, about 300/s, three times what the
ramp proved it could take). That result is worth keeping, because it's what overload looks like
here:

| | 300 checkouts/s for 10 s |
|---|---|
| failed requests | 8 672 of 64 185 (13.5 %), browse 0 |
| failed checkouts | 3 263 of 4 800 |
| app log | `Connection is not available, request timed out after 10000ms (total=10, active=10, idle=0, waiting=200)` |

Browse wasn't affected at all, since catalog-service is a separate service with its own pool.
That's the bulkhead that splitting the monolith bought. Checkout, though, queued without limit:
every request waited 10 s for a connection before failing, and the backlog outlasted the spike.
Nothing sheds load early. A limit on concurrent checkouts that answers 503 at once (a bulkhead in
front of the pool) would fail a few requests fast instead of making them all slow. That was a
follow-up, and KI-005 added it: 8 concurrent checkouts, the rest refused with a 503 at once. The
spike above has NOT been re-run against it, so the effect on those numbers is unmeasured.

## Concepts, briefly

- **Throughput vs latency.** Throughput is how much gets done per second. Latency is how long one
  request takes. Past the knee, throughput stops rising and latency is all queueing.
- **Percentiles.** p50 is the typical user; p95 and p99 are the unlucky ones, and at 1 400 req/s
  "1 %" is 14 people every second. Averages hide tails: the baseline's mean poll was fast while its
  p99 was 21.8 s.
- **Load, stress, spike, soak.**
  - Load: expected traffic (`steady`).
  - Stress: past it, to find the knee (`ramp`).
  - Spike: a sudden burst, then does it recover? (`spike`)
  - Soak: hours at normal load, for leaks and slow growth. Not run here; it needs hours.
- **Bottleneck analysis.** Find where the work waits, not where it's slow. Here the HTTP
  endpoints were fast. The wait was in a table, visible only as `published_at - created_at`.
- **Virtual threads** (Java 21). A request blocked on I/O stops holding a platform thread, so
  Tomcat's 200-thread ceiling stops mattering. They don't add database connections: with a pool of
  10, the 11th concurrent query still waits. Nothing measured here was thread-bound (the app's max
  latency was 0.22 s during the baseline, with up to 22 requests waiting for a connection), so they
  aren't switched on in this phase.

## Follow-ups (not done)

- Keep the remote stock check out of the checkout transaction (`placeOnce()`), so a connection is
  held only for the writes.
- Shed load at checkout: a concurrency limit that answers 503 with `Retry-After` at once, instead
  of 200 requests waiting 10 s each for a connection.
- The gateway is the first CPU limit for reads: measure the cost of the per-request rate-limit
  Redis call and JWT verification, and run more than one gateway replica.

- Pipelined outbox sends: send the whole batch, then wait for all acks. Another multiple on
  throughput, but it needs care with per-order ordering when a send fails.
- A shorter `poll-delay` (for example 200 ms) to lower the ~1.5 s settle floor, which is 3 outbox
  hops of ~0.5 s average wait each.
- Push instead of poll for order status (SSE or a webhook), which removes the polling that turned a
  slow saga into an edge overload.
- Run the load generator on a separate machine for absolute numbers.
- A soak test.
