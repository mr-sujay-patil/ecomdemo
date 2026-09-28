# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-28
- **Phase:** 24 — Distributed Transactions (Saga pattern, choreography over Kafka)
- **Branch:** feature/phase-24-saga (cut from `main` at `a2d5b8c`)
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 23 merge verification — PASSED, `phase-23-complete` TAGGED at `a2d5b8c`
PR #37 merged as a merge commit (2 parents); 0 missing commits, 0 diffs, branch alive; CI on `main`
green; `verify` on `main` BUILD SUCCESS (483 tests, 0 failed/skipped, stack down); cold smoke
**327/0/0** (this machine).

## Checklist (from the phase file's "What you'll implement")
- [ ] A new mock `payment-service`
- [ ] The saga: `OrderCreated` (PENDING) → `StockReserved` / `StockRejected` →
      `PaymentCompleted` / `PaymentFailed` → `CONFIRMED` / `CANCELLED`
- [ ] Compensation that releases stock when payment fails
- [ ] Outbox publishers and idempotent consumers throughout
- [ ] An order status endpoint
- [ ] The orchestration alternative documented
- [ ] End-to-end test: success and failure both end consistently; smoke: CONFIRMED, and a forced
      payment failure ends CANCELLED with the stock restored
- [ ] README, decisions, test report, RECENT rotation, tracker 🔵

## Planning decisions
- (to be filled after surveying the current checkout flow)

## Next action
Survey the current checkout (app order + inventory reservation + outbox + Kafka topics), record the
saga design under "Planning decisions", then implement the checklist in order.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- VM pauses (check `dmesg | grep TimeSync`), Windows port reservations, ~12 s DNS for a stopped container.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).

## ⚠️ Carried, not fixed (oldest first)
- A failed compensating release leaks a reservation; nothing reconciles it. → Phase 24
- The CSV import is a distributed write with no shared transaction (restartable, idempotent). → Phase 24
- **HS256 with a shared secret** — every service can mint as well as verify.
- `ecomdemo-app` is not yet named `order-service`.
- catalog-service has no springdoc; the gateway does no request logging.
- The gateway's `/api/products` route has no breaker; inventory calls have no resilience policy.
- Phase 21 recorded no entries in `docs/decisions.md`; `application.properties` still describes the
  removed customer proxy.
- catalog-service is slow on its first requests after a restart (a half-open trial can exceed 500 ms).
