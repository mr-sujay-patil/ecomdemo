# Phase 1: Baseline Monolith

| | |
|---|---|
| **Stage** | Stage 1: Foundation |
| **Technology** | Spring Boot + H2 |
| **Branch** | `feature/phase-01-baseline-monolith` |
| **PR title** | `Phase 01: Baseline Monolith` |
| **Requires** | `phase-00-complete` tag exists on `main` |
| **Completion tag** | `phase-01-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Spring Boot (Web, Data JPA, Validation) + H2 in-memory database

**Goal:** Understand how an e-commerce backend works end to end, with nothing else in the way.

**What you'll implement**
- Generate the project (latest stable Spring Boot 4.x, verified on start.spring.io) with the Maven Wrapper. Base package `com.ecomdemo`.
- `product` feature: CRUD (id, name, description, price, stockQuantity).
- `cart` feature: a single shared cart (no users). Add, update, and remove items, and view the cart with a server-calculated total.
- `order` feature: place an order from the cart (check stock, reduce stock, save order, empty cart), list orders, and get an order by id. Status is `PLACED`.
- `common` package: a `@RestControllerAdvice` returning `{ status, message }` for 404, 400, and 409.
- Seed about 10 products through `data.sql` (`spring.jpa.defer-datasource-initialization=true`). H2 console at `/h2-console`.
- Layering: Controller → Service → Repository, with DTOs as Java records. All endpoints under `/api`.
- One `@SpringBootTest` verifying the place-order flow.
- `scripts/smoke-test.sh`: the end-to-end curl smoke test described in the Testing & Acceptance Protocol, extended in every later phase.
- README: how to run the app, plus a curl walkthrough.

**Concepts to understand**
- Spring Boot auto-configuration and starters
- The responsibility of each layer
- JPA entities and relationships; lazy vs eager loading
- Why entities are never returned from controllers
- Bean Validation
- Why `BigDecimal` is used for money

**Done when**
- `./mvnw spring-boot:run` starts the app.
- The curl flow works: list products → add to cart → view cart → place order → view order → stock decreased.
- The PR is merged and the verification checklist passes.

**Not in this phase:** security, a real database, Docker, extra tests, Swagger.

## Smoke test additions (`scripts/smoke-test.sh`)

Create `scripts/smoke-test.sh` with: list products → add to cart → view cart → place order → get order → confirm the stock decreased. Negative cases: unknown product (404), invalid input (400), quantity above stock (409). The script exits non-zero on any failure and prints a PASS/FAIL line per check.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.
