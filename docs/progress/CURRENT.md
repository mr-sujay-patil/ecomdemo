# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-23
- **Phase:** 19: Modular Monolith (Spring Modulith)
- **Branch:** feature/phase-19-modulith
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #24 — raised, CI green (`Build and test` SUCCESS), mergeStateStatus CLEAN
  https://github.com/mr-sujay-patil/ecomdemo/pull/24
- **Waiting for user:** YES — review and merge

## Phase 18 merge verification (PASSED 2026-09-23)
PR #23 merged as merge commit 90d5d68 (parents a70537f + 3ea4c1d). Branch is an ancestor of
`main`, no missing commits, no file diff, all 20 remote branches intact, V10 present, and both
classes the phase deleted (`OrderEventPublisher`, `MessagingAsyncConfig`) are gone from `main`.
CI on `main` green at 90d5d68. `./mvnw clean verify` on `main` -> **310 + 82**, 0 failures,
0 skipped. `scripts/smoke-test.sh` on `main` -> **270 passed, 0 failed, 0 skipped**.
Tag `phase-18-complete` pushed, pointing at 90d5d68.

## A process question that was raised and CLOSED (2026-09-23)
Mid-phase the user asked for targeted tests only, and no full builds or `docker compose up`
without asking. That contradicts execution-protocol §5 and testing-protocol, so it was raised
rather than resolved unilaterally. The user's answer: **"Skip my recent instruction and follow the
same instructions that we have decided on earlier."** So the protocol stands unchanged - full
`./mvnw clean verify` and `scripts/smoke-test.sh` at merge verification and before every PR. The
memory written for the interim preference has been deleted. Do not re-raise this.

## Checklist (copied from the phase's "What you'll implement")
- [x] Modules: 14 of them, each with a public API, an `internal` package and a DECLARED
      allowedDependencies list that the build enforces
- [x] Events replace direct cross-module calls where appropriate — mostly already true from
      Phases 16 and 18; `order -> cart` stays a direct call ON PURPOSE (same transaction)
- [x] `ModularityTest` (verify + regenerates docs/modules/), plus `StockMutationRulesTest`
- [x] Done when: verification passes, graph is ACYCLIC and declared
- [x] Smoke test: **270 passed, 0 failed — UNCHANGED**, which is this phase's real result
- [x] Testing protocol run in full + docs/test-reports/phase-19.md
- [x] README section, docs/decisions.md entries (8), RECENT.md rotation (Phase 17 archived),
      tracker -> 🔵
- [x] PR raised (#24)

## Where the code is today (surveyed, not yet moved)
Domain-ish: `product` (6), `customer` (6), `cart` (6), `order` (12), `notification` (4).
Infrastructure/cross-cutting: `batch` (17), `messaging` (14), `security` (9), `common` (7),
`auth` (5), `metrics` (4), `cache` (4), `logging` (3).
The phase names modules `catalog` and `inventory` that do NOT exist as packages yet - `catalog` is
today's `product`, and `inventory` is the stock behaviour currently inside `Product`/`ProductService`
and exercised by `OrderPlacementService`. Deciding how far to split those is the first real
decision of this phase.

## Last test run
- 2026-09-23: `./mvnw clean verify` -> BUILD SUCCESS, Surefire **319** (was 310) + Failsafe **82**
  (unchanged), 0 failures, 0 skipped, 1m58s.
- 2026-09-23: `scripts/smoke-test.sh` -> **270 passed, 0 failed, 0 skipped — UNCHANGED**. This
  phase adds no checks, so the existing 270 were the whole regression net for a refactor that moved
  ~90 files. Not one changed.

## Open issues / blockers
- ✅ CLOSED 2026-09-23: the Grafana dashboards' RENDER and Kafka UI, carried since Phase 15, have
  now been looked at by the user. Both render correctly. Do NOT re-raise this.
  - Verified: checkout latency draws three quantile lines (the histogram buckets work), checkout
    rate by outcome is stacked and shows both `placed` and `empty_cart`, database `pending` is flat
    at zero, the Logs dashboard returns both lines for one correlation ID, and Kafka UI shows 25
    messages across 3 partitions keyed by order id with an empty DLT.
  - NOTE: the Phase 15 report's manual steps name a login `asha` that does not exist. The working
    credentials are `smoke-customer` / `smoke-test-password` (API) and `admin` / `admin` (Grafana).
- ❗ NEW KNOWN DEFECT, found by that verification, NOT fixed in this phase: two of the four stat
  panels on `EcomDemo Overview` mislead. `Orders placed / min` showed 0.00 for an hour in which
  Prometheus confirms 34.26 orders occurred, and `Failed checkouts` showed a STALE 11.82% because
  its ratio goes NaN and `lastNotNull` skips nulls but not zeros. Full entry in `docs/decisions.md`.
  Agreed plan: fix on its own `fix/dashboard-stat-reducers` branch AFTER PR #24 merges, following
  the Phase 13 cache-defect precedent. Not a blocker for Phase 19.

## Decisions this phase (copied to docs/decisions.md ✅ — 8 entries)
- Modulith as `-api` (compile, annotations go on main source) + `-core`/`-docs` (test). Boot 4.1.1
  does not manage Spring Modulith and the GA line targets Boot 3.5; keeping the runtime starter out
  is what makes the gap safe.
- The verification test was written FIRST, before any code moved. It found 2 problems, not dozens,
  and showed the cycle was ONE class.
- `CurrentUser` -> customer and `JwtConfig.Claims` -> `shared.TokenClaims` broke the only cycle.
- `catalog` + `inventory` over ONE table and ONE entity: behaviour split, schema not.
- The ArchUnit stock rule permits catalog AS WELL AS inventory, and says why out loud.
- Every module declares allowedDependencies; `shared` and `messaging` declare none.
- Cross-module repository access replaced by 3 narrow APIs, not by exposing repositories.
- Docs generated by a TEST into a committed `docs/modules/`.

## Environment left behind
Docker Desktop running but the compose stack is **DOWN**. It was brought up to run the smoke test
and shut down again at the user's request. Named volumes were kept, so `docker compose up -d`
restores the data. Schema on that volume is **V10**. The SonarQube stack is also stopped;
`docker compose -f compose.sonar.yaml up -d` brings it back. `.env` holds a real JWT_SECRET and is
gitignored. An untracked `handoff.md` (a pasted Phase 15 session transcript) sits in the working
tree, unstaged and unused - not this session's file, so it has been left alone.

## Next action
STOPPED at the mandatory post-PR stop point. Everything is done and green.
- If they say `merged, continue` -> merge verification (execution-protocol §5) on `main`, then tag
  `phase-19-complete` and start Phase 20 (`docs/phases/phase-20-microservices.md`).
- If they say `changes: <feedback>` -> back to IMPLEMENTING on this same branch.
- Do NOT merge unless they say so explicitly.
- Phase 20 inherits two open questions this phase deliberately left: whether to split `product` and
  `product_stock` (which would let the stock rule name ONE module), and whether `catalog` should
  contribute its own cache configuration so `cache` stops knowing products exist.
