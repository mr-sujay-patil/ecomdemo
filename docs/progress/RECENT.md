# Recent Phase Summaries (rolling window: last 2 phases)

> Newest first. When a third summary is added, move the oldest to `docs/progress/archive/phase-XX-summary.md`. Maximum ~30 lines per summary: facts only, no narrative.

<!-- TEMPLATE
## Phase XX: <Title> (tag: phase-XX-complete, PR #N)
**What exists now:** <1–3 lines describing the system after this phase>
**Key code:** <packages and classes that matter next>
**Config & infrastructure:** <profiles, env vars, ports, containers, and how to run>
**Tests:** <new test classes, smoke test additions>
**Gotchas:** <anything surprising the next phase must know>
**Follow-ups (not done, out of scope):** <suggestions deferred to later phases>
-->

## Phase 01: Baseline Monolith (tag: pending, PR: pending)
**What exists now:** A running Spring Boot 4.1.1 monolith on H2 in-memory: product CRUD, one
shared cart with a server-calculated total, and checkout that validates stock, reduces it, saves
the order and empties the cart. All endpoints under `/api`, errors as `{status, message}`.
**Key code:** `com.ecomdemo.{product,cart,order,common}`, package-by-feature, Controller → Service
→ Repository. `CartService.currentCart()` is the single-cart rule. `OrderService.place()` is the
checkout flow. `GlobalExceptionHandler` maps 404/400/409.
**Config & infrastructure:** `application.properties` — H2 at `jdbc:h2:mem:ecomdemo`,
`ddl-auto=create-drop`, `defer-datasource-initialization=true`, `open-in-view=false`, H2 console
at `/h2-console`. `data.sql` seeds 10 products (product 10 has stock 2, used by the 409 check).
Port 8080. Build with JDK 21: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
**Tests:** `PlaceOrderFlowTest` (`@SpringBootTest`, the only test this phase by design).
`scripts/smoke-test.sh` — 34 checks, re-runnable, exits non-zero on failure.
**Gotchas:** `save()` on a detached entity merges and returns a copy with *uninitialised* lazy
proxies — cart writes therefore re-read via `CartRepository.findCart()` (JOIN FETCH) before
mapping. `open-in-view=false` means repositories must fetch everything the caller needs. Order's
table is `orders` (ORDER is reserved). Order lines snapshot name and price on purpose.
**Follow-ups (not done, out of scope):** checkout is not atomic and can oversell under
concurrency — Phase 6 (`@Transactional` + `@Version`). No unit/slice tests yet — Phase 2.

## Phase 00: Repository Bootstrap (tag: phase-00-complete, no PR by design)
**What exists now:** Public repo github.com/mr-sujay-patil/ecomdemo with the docs package,
README, .gitignore, PR template and `scripts/`. `main` protected: PR required, force pushes and
deletions blocked, `enforce_admins: true`, merge commits only (squash and rebase disabled).
**Key code:** none (no application code in this phase).
**Config & infrastructure:** GitHub settings applied via `gh api`; a direct push to `main` is
rejected with GH006 (verified, not assumed).
**Tests:** none applicable; no build exists yet.
**Gotchas:** the earlier attempt at this roadmap was renamed to `EcomDemo-archive` to free the
name. Phase 0 is the only phase permitted to commit to `main`.
**Follow-ups (not done, out of scope):** CI checks on the protected branch — Phase 11.
