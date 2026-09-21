# EcomDemo

A learning project: an e-commerce application that evolves from a simple Spring Boot monolith into a
production-grade distributed system, **one technology per phase**. Each phase introduces exactly one
new technology, on its own feature branch, merged into `main` through a reviewed Pull Request.

**Stack:** Java 21 · Spring Boot 4.1.1 · PostgreSQL 18 · springdoc-openapi · Maven Wrapper · Git + GitHub

## Current status

**Phase 4: PostgreSQL** — the application now stores its data in a real PostgreSQL server
instead of an in-memory database, so **a product you create is still there after a restart**.
Configuration is split into profiles: `dev` talks to PostgreSQL and is the default, `test` keeps
the 102-test suite on in-memory H2 so `./mvnw clean verify` needs nothing running. Connection
details come from environment variables with local defaults.

Everything from the earlier phases still stands: products, a single shared cart and order
placement, documented by an OpenAPI 3 spec at `/v3/api-docs` and Swagger UI at
**<http://localhost:8080/swagger-ui.html>**. No schema migrations, no security and no Docker
Compose yet; those arrive in Phases 5, 8 and 10.

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
│   ├── application.properties       # shared by every profile
│   ├── application-dev.properties   # PostgreSQL + Hikari (the default profile)
│   └── data.sql                     # 10 seed products, guarded so re-running is a no-op
├── src/test/java/com/ecomdemo/
│   ├── support/   # TestData fixture builders
│   └── <feature>/ # *ServiceTest, *ControllerTest, *RepositoryTest per feature
├── src/test/resources/
│   └── application-test.properties  # in-memory H2 for the suite
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

Requires **JDK 21** on the path, and **Docker** (or a native PostgreSQL) for the database.

### 1. Start PostgreSQL

One command, and it keeps its data in a Docker volume between restarts:

```bash
docker run --name ecomdemo-postgres \
  -e POSTGRES_DB=ecomdemo -e POSTGRES_USER=ecomdemo -e POSTGRES_PASSWORD=ecomdemo \
  -p 5432:5432 -d postgres:18-alpine
```

After the first time, start and stop the same container instead of creating a new one — `docker
run` again would fail on the name, and `docker rm` would throw the data away:

```bash
docker start ecomdemo-postgres          # bring it back up
docker stop  ecomdemo-postgres          # shut it down, data kept
docker rm -f ecomdemo-postgres          # delete it AND its data, to start clean
```

Prefer a native install? Anything that gives you a `ecomdemo` database owned by a `ecomdemo` user
on port 5432 works — or point the application somewhere else with the environment variables below.

### 2. Start the application

```bash
./mvnw spring-boot:run          # starts on http://localhost:8080, dev profile
```

On the first start Hibernate creates the five tables and `data.sql` seeds ten products. On every
start after that the schema is left alone and the seed does nothing, because it is guarded — see
[`data.sql`](src/main/resources/data.sql).

In a second terminal:

```bash
./mvnw clean verify             # build and run all 102 tests (no database needed)
scripts/smoke-test.sh           # 52-54 end-to-end checks against the running app
```

Swagger UI is at <http://localhost:8080/swagger-ui.html> — every endpoint is listed with its
parameters, example bodies and error responses, and *Try it out* calls the running application.

### Configuration

| Variable | Default | What it is |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | Database host |
| `POSTGRES_PORT` | `5432` | Database port |
| `POSTGRES_DB` | `ecomdemo` | Database name |
| `POSTGRES_USER` | `ecomdemo` | Database user |
| `POSTGRES_PASSWORD` | `ecomdemo` | Database password |

The defaults are throwaway local credentials, which is why they can live in Git. A real password is
passed in as `POSTGRES_PASSWORD` and never written into a file:

```bash
POSTGRES_HOST=db.example.com POSTGRES_PASSWORD="$PROD_DB_PASSWORD" ./mvnw spring-boot:run
```

Two profiles select which database is used:

| Profile | Database | Selected by |
|---|---|---|
| `dev` | PostgreSQL | the default, set in `application.properties` |
| `test` | in-memory H2 | the surefire configuration in `pom.xml`, for every test JVM |

That split is why `./mvnw clean verify` passes with no PostgreSQL running. It also means the tests
do not yet prove the application works on PostgreSQL — "works on H2" is not "works on
PostgreSQL", which is exactly what Phase 7 fixes with Testcontainers.

### Seeing that the data is real

```bash
# create a product, restart the app, and it is still there
curl -X POST http://localhost:8080/api/products -H 'Content-Type: application/json' \
  -d '{"name":"Survivor","description":"still here after a restart","price":10.00,"stockQuantity":1}'
```

Or look at the rows directly. The H2 console is gone; point **DBeaver** or **pgAdmin** at
`localhost:5432`, database `ecomdemo`, user `ecomdemo`, password `ecomdemo` — or use `psql` in the
container:

```bash
docker exec -it ecomdemo-postgres psql -U ecomdemo -d ecomdemo

\dt                              -- the five tables Hibernate generated
\d product                       -- the columns and types it chose
SELECT * FROM product;           -- the seeded catalogue
```

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

The table above is a summary; the API describes itself in full:

| Path | What it serves |
|---|---|
| `/swagger-ui.html` | Swagger UI — every endpoint, browsable and callable |
| `/v3/api-docs` | The OpenAPI 3 document as JSON |
| `/v3/api-docs.yaml` | The same document as YAML |

The document is **generated from the code** at startup, not hand-written: springdoc scans the
controllers for `@Tag`, `@Operation` and `@ApiResponse`, the DTO records for `@Schema`, and the
Bean Validation annotations for `required`, `maxLength` and `minimum`. That is the *code-first*
approach — the code is the source of truth, so the description cannot drift away from it. The
alternative, *contract-first*, means writing the specification first and generating stubs from it;
it earns its keep when several teams have to agree on an API before anyone writes code.

Feed the document to anything that speaks OpenAPI:

```bash
curl -s localhost:8080/v3/api-docs | python3 -m json.tool | head -40
curl -s localhost:8080/v3/api-docs.yaml -o ecomdemo-api.yaml   # e.g. for a generated client
```

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

`./mvnw clean verify` runs all 102 tests in about eight seconds, against in-memory H2 — no
PostgreSQL needed. They sit at four levels, each loading only what it needs:

| Level | Annotation | What it loads | Classes |
|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | Nothing — plain objects with mocked collaborators | `ProductServiceTest`, `CartServiceTest`, `OrderServiceTest` |
| Web slice | `@WebMvcTest` | The controller, JSON conversion, validation and the error handler; services are `@MockitoBean` | `ProductControllerTest`, `CartControllerTest`, `OrderControllerTest` |
| Persistence slice | `@DataJpaTest` | JPA and an H2 database; no web layer | `CartRepositoryTest`, `OrderRepositoryTest` |
| Full context | `@SpringBootTest` | The whole application | `PlaceOrderFlowTest`, `OpenApiDocumentationTest` |
| Configuration | `ApplicationContextRunner` | Only the properties files, resolved as at startup | `DatasourceConfigurationTest` |

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
- **The schema is generated, not migrated.** `ddl-auto=update` lets Hibernate add tables and
  columns at startup, and it will never drop or rename anything — so a change that needs one is
  silently not applied, and nothing records which version of the schema a database is on.
  **Phase 5** replaces it with Flyway migrations.
- **No authentication.** Every endpoint is open and there is one cart for the whole world.
  **Phases 8 and 9** add Spring Security and JWT.
- **No coverage report.** The suite is broad but nothing measures or enforces how much of the
  code it reaches. **Phase 12** adds JaCoCo and SonarQube.
- **Tests run against H2, not the real database.** H2 accepts some SQL PostgreSQL would reject,
  so a green suite is not yet proof the queries work in production. **Phase 7** adds
  Testcontainers.
