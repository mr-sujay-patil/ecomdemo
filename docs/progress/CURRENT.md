# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-23
- **Phase:** 19: Modular Monolith (Spring Modulith)
- **Branch:** feature/phase-19-modulith
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

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
- [ ] Modules: `catalog`, `inventory`, `customer`, `cart`, `order`, `notification`, `shared`,
      each with a public API and an `internal` package
- [ ] Events replace direct cross-module calls where appropriate
- [ ] An `ApplicationModules.verify()` test and generated documentation
- [ ] Done when: the verification test passes and the diagrams show clean dependencies
- [ ] Smoke test: NO new checks - the existing script must still pass UNCHANGED (270 checks)
- [ ] Testing protocol run in full + docs/test-reports/phase-19.md
- [ ] README section, docs/decisions.md entries, RECENT.md rotation (Phase 17 archived),
      tracker -> 🔵
- [ ] PR raised

## Where the code is today (surveyed, not yet moved)
Domain-ish: `product` (6), `customer` (6), `cart` (6), `order` (12), `notification` (4).
Infrastructure/cross-cutting: `batch` (17), `messaging` (14), `security` (9), `common` (7),
`auth` (5), `metrics` (4), `cache` (4), `logging` (3).
The phase names modules `catalog` and `inventory` that do NOT exist as packages yet - `catalog` is
today's `product`, and `inventory` is the stock behaviour currently inside `Product`/`ProductService`
and exercised by `OrderPlacementService`. Deciding how far to split those is the first real
decision of this phase.

## Last test run
- 2026-09-23 (on `main`, merge verification): `./mvnw clean verify` -> 310 + 82, 0 failures,
  0 skipped, 1m39s. `scripts/smoke-test.sh` -> 270 passed, 0 failed, 0 skipped.

## Open issues / blockers
- Carried from Phases 15-17 and still unseen by human eyes: the Grafana dashboards' RENDER, and
  Kafka UI at http://localhost:8090. Both need the stack up (`docker compose up -d`), which is
  currently DOWN. Steps: `docs/test-reports/phase-15.md` §8.

## Decisions this phase
- (none yet)

## Environment left behind
Docker Desktop running but the compose stack is **DOWN** - `docker compose down` was run at the
user's request after Phase 18's verification. Named volumes were kept, so `docker compose up -d`
restores the data. Schema on that volume is **V10**. The SonarQube stack is also stopped;
`docker compose -f compose.sonar.yaml up -d` brings it back. `.env` holds a real JWT_SECRET and is
gitignored. An untracked `handoff.md` (a pasted Phase 15 session transcript) sits in the working
tree, unstaged and unused - not this session's file, so it has been left alone.

## Next action
Start step 5 (IMPLEMENTING). Order that keeps the build green throughout:
1. Add `spring-modulith` (BOM + starter + the test artifact), and write the
   `ApplicationModules.verify()` test FIRST so the current violations are visible before anything
   moves.
2. Decide the module map (see "Where the code is today"), record it in `docs/decisions.md`.
3. Move packages one module at a time, `./mvnw test` after each.
4. Generate the documentation (C4/PlantUML) into `docs/modules/`.
