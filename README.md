# EcomDemo

A learning project: an e-commerce application that evolves from a simple Spring Boot monolith into a
production-grade distributed system, **one technology per phase**. Each phase introduces exactly one
new technology, on its own feature branch, merged into `main` through a reviewed Pull Request.

**Stack:** Java 21 · Spring Boot 4.1.1 · H2 (in-memory) · Maven Wrapper · Git + GitHub

## Current status

**Phase 1: Baseline Monolith** — a running Spring Boot application with products, a single shared
cart and order placement, backed by an in-memory H2 database. No security, no real database and no
Docker yet; those arrive in Phases 8, 4 and 10.

## Roadmap

The full 32-phase plan, with a progress tracker, lives in **[docs/ROADMAP.md](docs/ROADMAP.md)**.

## Repository layout

```
ecomdemo/
├── src/main/java/com/ecomdemo/
│   ├── common/    # ApiError + @RestControllerAdvice shared by every feature
│   ├── product/   # catalogue CRUD
│   ├── cart/      # the single shared cart
│   └── order/     # checkout and order history
├── src/main/resources/
│   ├── application.properties
│   └── data.sql   # 10 seed products
├── docs/          # roadmap, phase specs, process docs, decisions, progress
├── scripts/       # smoke-test.sh
└── .github/       # Pull Request template (workflows from Phase 11)
```

Each feature package is self-contained and layered **Controller → Service → Repository**. DTOs are
Java records; entities never leave the service layer.

## How to work on this project

Every phase follows the same cycle, documented in
[docs/process/git-workflow.md](docs/process/git-workflow.md):

1. Cut `feature/phase-XX-<slug>` from the latest `main`.
2. Implement only that phase, in small Conventional Commits.
3. Run the full [testing protocol](docs/process/testing-protocol.md).
4. Raise a Pull Request into `main` and review it.
5. Merge with **"Create a merge commit"**, then tag `phase-XX-complete`.

Branches are never deleted — they are the permanent history of the learning journey.

## Running it

Requires **JDK 21** on the path. Nothing else — the database is in-memory and the Maven Wrapper
fetches Maven itself.

```bash
./mvnw spring-boot:run          # starts on http://localhost:8080
```

In a second terminal:

```bash
./mvnw clean verify             # build and run all tests
scripts/smoke-test.sh           # 34 end-to-end checks against the running app
```

The H2 console is at <http://localhost:8080/h2-console> — JDBC URL `jdbc:h2:mem:ecomdemo`, user
`sa`, empty password. The schema is recreated and re-seeded on every start, so anything you change
is gone on restart. Phase 4 swaps H2 for PostgreSQL and Phase 5 adds Flyway migrations.

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/products` | List the catalogue |
| `GET` | `/api/products/{id}` | One product |
| `POST` | `/api/products` | Create a product (201 + `Location`) |
| `PUT` | `/api/products/{id}` | Replace a product |
| `DELETE` | `/api/products/{id}` | Delete a product (204) |
| `GET` | `/api/cart` | The shared cart with its server-calculated total |
| `POST` | `/api/cart/items` | Add a product, or increase an existing line |
| `PUT` | `/api/cart/items/{productId}` | Set the quantity of a line |
| `DELETE` | `/api/cart/items/{productId}` | Remove a line |
| `POST` | `/api/orders` | Check out the whole cart (201 + `Location`) |
| `GET` | `/api/orders` | Order history |
| `GET` | `/api/orders/{id}` | One order |

Errors always come back as `{ "status": ..., "message": ... }`: **404** for a missing entity,
**400** for a request that fails validation, **409** for a valid request that conflicts with the
current state (empty cart, not enough stock).

## Walkthrough

```bash
# 1. See what is for sale
curl -s localhost:8080/api/products | head -c 400

# 2. Put two mechanical keyboards in the cart
curl -s -X POST localhost:8080/api/cart/items \
  -H 'Content-Type: application/json' \
  -d '{"productId": 1, "quantity": 2}'

# 3. Look at the cart - totalAmount is computed by the server, never sent by the client
curl -s localhost:8080/api/cart

# 4. Check out. Takes no body: it always orders the whole cart
curl -s -X POST localhost:8080/api/orders

# 5. Read the order back
curl -s localhost:8080/api/orders/1

# 6. Stock went down by 2, and the cart is empty again
curl -s localhost:8080/api/products/1
curl -s localhost:8080/api/cart
```

Now try the failures:

```bash
# 404 - no such product
curl -s -i localhost:8080/api/products/9999 | head -1

# 400 - Bean Validation rejects quantity 0 before any of our code runs
curl -s -i -X POST localhost:8080/api/cart/items \
  -H 'Content-Type: application/json' \
  -d '{"productId": 1, "quantity": 0}' | head -1

# 409 - the cart accepts it, checkout refuses it (product 10 has only 2 in stock)
curl -s -X POST localhost:8080/api/cart/items \
  -H 'Content-Type: application/json' -d '{"productId": 10, "quantity": 99}'
curl -s -X POST localhost:8080/api/orders
```

## Known gaps (closed by later phases)

- **Checkout is not atomic.** Each save commits on its own, so a crash mid-checkout can reduce
  stock without producing an order, and two simultaneous checkouts can oversell the last unit.
  Stock is validated for every line before anything is written, which keeps the common case
  clean, but the race is real. **Phase 6** fixes it with `@Transactional` and optimistic locking.
- **Everything is in-memory.** Restarting loses all data. **Phase 4** introduces PostgreSQL.
- **No authentication.** Every endpoint is open and there is one cart for the whole world.
  **Phases 8 and 9** add Spring Security and JWT.
