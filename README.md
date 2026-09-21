# EcomDemo

A learning project: an e-commerce application that evolves from a simple Spring Boot monolith into a
production-grade distributed system, **one technology per phase**. Each phase introduces exactly one
new technology, on its own feature branch, merged into `main` through a reviewed Pull Request.

**Stack:** Java 21 · Spring Boot 4.1.1 · H2 (in-memory) · Maven Wrapper · Git + GitHub

## Current status

**Phase 2: Automated Testing** — the Phase 1 application (products, a single shared cart and
order placement on in-memory H2) now under a 79-test safety net: Mockito unit tests, `@WebMvcTest`
web slices and `@DataJpaTest` persistence slices. No security, no real database and no Docker yet;
those arrive in Phases 8, 4 and 10.

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
├── src/test/java/com/ecomdemo/
│   ├── support/   # TestData fixture builders
│   └── <feature>/ # *ServiceTest, *ControllerTest, *RepositoryTest per feature
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
./mvnw clean verify             # build and run all 79 tests
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

## Tests

`./mvnw clean verify` runs all 79 tests in about nine seconds. They sit at four levels, each
loading only what it needs:

| Level | Annotation | What it loads | Classes |
|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | Nothing — plain objects with mocked collaborators | `ProductServiceTest`, `CartServiceTest`, `OrderServiceTest` |
| Web slice | `@WebMvcTest` | The controller, JSON conversion, validation and the error handler; services are `@MockitoBean` | `ProductControllerTest`, `CartControllerTest`, `OrderControllerTest` |
| Persistence slice | `@DataJpaTest` | JPA and an H2 database; no web layer | `CartRepositoryTest`, `OrderRepositoryTest` |
| Full context | `@SpringBootTest` | The whole application | `PlaceOrderFlowTest` |

That shape is the **test pyramid**: many fast tests where the logic lives, fewer slow ones as more
of the framework is loaded. A failing unit test can only mean the service is wrong; a failing
`@SpringBootTest` could mean anything, which is why there is one of them and not eighty.

Conventions, if you add tests:

- Name them `methodName_condition_expectedResult`, and structure the body Given / When / Then.
- Assert with AssertJ (`assertThat(...)`). Compare money with `isEqualByComparingTo`, never
  `isEqualTo` — `BigDecimal.equals()` compares the scale too, so `8999.00` and `8999.0` are
  "different". For the same reason web tests assert the JSON body rather than a deserialised DTO.
- Build entities with `com.ecomdemo.support.TestData` rather than adding setters to them.
- Mock at the service boundary, so a failure names one class.

```bash
./mvnw test -Dtest=OrderServiceTest          # one class
./mvnw test -Dtest='*ControllerTest'         # all web slices
```

## Known gaps (closed by later phases)

- **Checkout is not atomic.** Each save commits on its own, so a crash mid-checkout can reduce
  stock without producing an order, and two simultaneous checkouts can oversell the last unit.
  Stock is validated for every line before anything is written, which keeps the common case
  clean, but the race is real. **Phase 6** fixes it with `@Transactional` and optimistic locking.
- **Everything is in-memory.** Restarting loses all data. **Phase 4** introduces PostgreSQL.
- **No authentication.** Every endpoint is open and there is one cart for the whole world.
  **Phases 8 and 9** add Spring Security and JWT.
- **No coverage report.** The suite is broad but nothing measures or enforces how much of the
  code it reaches. **Phase 12** adds JaCoCo and SonarQube.
- **Tests run against H2, not the real database.** H2 accepts some SQL PostgreSQL would reject,
  so a green suite is not yet proof the queries work in production. **Phase 7** adds
  Testcontainers.
