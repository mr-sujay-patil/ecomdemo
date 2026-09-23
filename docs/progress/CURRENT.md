# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-23
- **Phase:** 20: Microservices Split (multi-service architecture)
- **Branch:** feature/phase-20-microservices
- **Step:** PLANNING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** YES — the user asked for `plan first`. The plan is
  `docs/phases/phase-20-plan.md`. DO NOT WRITE CODE until they approve it.

## Phase 19 merge verification (PASSED 2026-09-23)
PR #24 merged as 6e96292, then follow-up PR #25 as ddc86f9 (parents 6e96292 + 672721d) for the two
defects the verification itself found: non-deterministic generated diagrams, and a smoke check that
had become a race when Phase 18's outbox put a 1s relay poll in the path. Branch is an ancestor of
`main`, no missing commits, no file diff, all 21 remote branches intact. CI on `main` green.
`./mvnw clean verify` on `main` -> **319 + 82**, 0 failures, 0 skipped, and the working tree stayed
CLEAN afterwards, which is the determinism fix proving itself. `scripts/smoke-test.sh` -> **270
passed, 0 failed**. Tag `phase-19-complete` pushed at ddc86f9.

## The memory ceiling, MEASURED (this decides the architecture)
Host **8 GB**; Docker Desktop capped at **3.8 GB** - which is why nine containers OOM-killed a
background task earlier. Measured at idle: a JVM is **296 MiB**, PostgreSQL is **31 MiB**,
kafka-ui 242, kafka 241, grafana 199, loki 59, alloy 42, prometheus 32, redis 9. Total ~1.15 GB.
**Five databases cost ~125 MiB more; five JVMs cost ~1.2 GB more.** So collapsing to one database
with a schema per service would save a rounding error and give up the phase's whole subject -
database-per-service STAYS. The levers are a 384M cap per service and putting kafka-ui behind a
Compose profile (~400 MiB back). Projection ~2.0-2.4 GB of 3.8 GB.

## Checklist (copied from the phase's "What you'll implement")
- [ ] Services: catalog, inventory, order (cart + orders + outbox + reports), customer (users +
      JWT), notification
- [ ] A database per service, each with its own Flyway migrations
- [ ] Synchronous calls through RestClient / HTTP Interface clients; async through Kafka
- [ ] JWT validation in each service
- [ ] All services in Compose
- [ ] Done when: the full purchase flow works across services
- [ ] Smoke test: runs against the individual services' ports, full flow still works end to end
- [ ] Testing protocol run in full + docs/test-reports/phase-20.md
- [ ] README section, docs/decisions.md entries, RECENT.md rotation (Phase 18 archived),
      tracker -> 🔵
- [ ] PR raised

## The two changes that carry the risk (see the plan for detail)
1. `catalog` and `inventory` SHARE the `product` table. Phase 19 split the behaviour and left the
   table, recording that Phase 20 is where it comes due. Splitting it touches the Phase 12
   optimistic lock, the Phase 16 cache eviction, the CSV import and the checkout reservation.
2. `CartItem` has a real FK to `product` and holds `@ManyToOne Product`. Cart goes to
   order-service, product to catalog-service - the FK cannot survive. It becomes what `OrderItem`
   already is (id + snapshotted name and price), which is a real behaviour change: the cart stops
   reflecting today's catalogue and starts reflecting the catalogue as at add-to-cart time.
Both are planned to happen INSIDE the monolith first (plan steps 2 and 3), while the full
270-check smoke test still applies end to end.

## Last test run
- 2026-09-23 (on `main`, Phase 19 merge verification): `./mvnw clean verify` -> 319 + 82, 0
  failures, 0 skipped, 1m31s. `scripts/smoke-test.sh` -> 270 passed, 0 failed, 0 skipped.

## Open issues / blockers
- The Phase 15 manual check (Grafana render, Kafka UI) is CLOSED - verified by the user on
  2026-09-23. Do not re-raise.
- KNOWN DEFECT carried from Phase 19, not fixed: two `EcomDemo Overview` stat panels mislead
  (`Orders placed / min` reads 0.00 for an hour containing 34 orders; `Failed checkouts` shows a
  STALE value because its ratio goes NaN and `lastNotNull` skips nulls but not zeros). Full entry
  in `docs/decisions.md`. Agreed: fix on its own `fix/dashboard-stat-reducers` branch.
- Grafana traps that cost time during Phase 19 verification and will again: (a) checking out a
  branch while Grafana runs replaces the bind-mounted provisioning dir's inode - fix with
  `docker compose up -d --force-recreate grafana`; (b) the admin password persists on the
  `grafana-data` volume and repeated 401s trip a 5-minute brute-force lockout.

## Decisions this phase
- (none yet — the plan is not approved)

## Environment left behind
Docker Desktop running; the compose stack is **DOWN**. Named volumes intact, schema **V10**.
The SonarQube stack is stopped. `.env` holds a real JWT_SECRET and is gitignored.

## Next action
STOPPED, awaiting approval of `docs/phases/phase-20-plan.md`.
- If they approve -> step 5 (IMPLEMENTING), working the plan's §8 order. Steps 2 and 3 of that
  order (the product/product_stock split and CartItem dropping its FK) happen INSIDE the monolith
  BEFORE any service is extracted, deliberately, so the riskiest data changes land while the
  270-check smoke test still covers them end to end.
- If they want changes -> revise the plan, stay in PLANNING.
- Do NOT write code before approval.
