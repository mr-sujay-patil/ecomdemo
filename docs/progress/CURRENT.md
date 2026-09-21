# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 1: Baseline Monolith (Spring Boot + H2)
- **Branch:** feature/phase-01-baseline-monolith
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Checklist (copied from the phase's "What you'll implement")
- [ ] Generate the project (latest stable Spring Boot 4.x) with the Maven Wrapper, base package `com.ecomdemo`
- [ ] `product` feature: CRUD (id, name, description, price, stockQuantity)
- [ ] `cart` feature: single shared cart — add, update, remove items, view with server-calculated total
- [ ] `order` feature: place order from cart (check stock, reduce stock, save, empty cart), list, get by id; status PLACED
- [ ] `common`: @RestControllerAdvice returning { status, message } for 404, 400, 409
- [ ] Seed ~10 products via data.sql (defer-datasource-initialization=true); H2 console at /h2-console
- [ ] Layering Controller → Service → Repository, DTOs as records, all endpoints under /api
- [ ] One @SpringBootTest verifying the place-order flow
- [ ] scripts/smoke-test.sh (end-to-end curl, PASS/FAIL per check, non-zero exit on failure)
- [ ] README: how to run + curl walkthrough
- [ ] Testing protocol run in full + docs/test-reports/phase-01.md
- [ ] decisions.md, RECENT.md, tracker → 🔵, PR raised

## Last test run
- n/a (not yet run)

## Phase 0 merge verification (recorded here per protocol)
Phase 0 has no PR by design — it bootstraps `main` itself. Verified on 2026-09-21:
bootstrap commit 6aa351e on origin/main; tag phase-00-complete pushed; repo settings confirmed
via gh api (merge commit only, squash/rebase off, delete_branch_on_merge false); branch protection
active with enforce_admins true — a real direct push to main was rejected with GH006.

## Open issues / blockers
- none

## Decisions this phase (copy to docs/decisions.md before the PR)
- (to be filled during implementation)

## Next action
Generate the Spring Boot project skeleton (pom.xml + Maven Wrapper + main class), verifying the
latest stable Spring Boot 4.x version on start.spring.io, then work down the checklist above with
small Conventional Commits.
