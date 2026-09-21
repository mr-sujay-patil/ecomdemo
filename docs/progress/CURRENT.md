# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 1: Baseline Monolith (Spring Boot + H2)
- **Branch:** feature/phase-01-baseline-monolith
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** see link below (raised, awaiting review)
- **Waiting for user:** YES — review and merge, then say `merged, continue`

## Checklist (copied from the phase's "What you'll implement")
- [x] Generate the project (Spring Boot 4.1.1) with the Maven Wrapper, base package `com.ecomdemo`
- [x] `product` feature: CRUD (id, name, description, price, stockQuantity)
- [x] `cart` feature: single shared cart — add, update, remove items, view with server-calculated total
- [x] `order` feature: place order from cart (check stock, reduce stock, save, empty cart), list, get by id; status PLACED
- [x] `common`: @RestControllerAdvice returning { status, message } for 404, 400, 409
- [x] Seed 10 products via data.sql (defer-datasource-initialization=true); H2 console at /h2-console
- [x] Layering Controller → Service → Repository, DTOs as records, all endpoints under /api
- [x] One @SpringBootTest verifying the place-order flow (PlaceOrderFlowTest)
- [x] scripts/smoke-test.sh — 34 checks, PASS/FAIL per check, non-zero exit on failure
- [x] README: how to run + curl walkthrough + API table + known gaps
- [x] Testing protocol run in full + docs/test-reports/phase-01.md
- [x] decisions.md, RECENT.md, tracker → 🔵, PR raised

## Last test run
- 2026-09-21: `./mvnw clean verify` → BUILD SUCCESS, Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
- 2026-09-21: app cold start → "Started EcomdemoApplication in 1.771 seconds", 0 ERROR/WARN lines
- 2026-09-21: `scripts/smoke-test.sh` → 34 passed, 0 failed, exit 0
- Report: `docs/test-reports/phase-01.md`

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ✅)
- Spring Boot 4.1.1 / Java 21; order lines snapshot name+price; cart total always derived;
  LAZY + JOIN FETCH with a re-read after cart writes; checkout validates-all-then-writes but is
  not yet atomic (Phase 6); table `orders`; open-in-view disabled.

## Next action
STOPPED at the mandatory post-PR stop point. Wait for the user.
- If they say `merged, continue` → run merge verification (execution-protocol §5) on `main`:
  re-run `./mvnw clean verify` and `scripts/smoke-test.sh`, run the git-workflow Verification
  Checklist, then tag and push `phase-01-complete`, then start Phase 2
  (`docs/phases/phase-02-testing.md`).
- If they say `changes: <feedback>` → back to IMPLEMENTING on this same branch.
- Do NOT merge unless they say exactly `approved, merge it`.
