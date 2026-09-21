# EcomDemo

A learning project: an e-commerce application that evolves from a simple Spring Boot monolith into a
production-grade distributed system, **one technology per phase**. Each phase introduces exactly one
new technology, on its own feature branch, merged into `main` through a reviewed Pull Request.

**Stack:** Java 21 · Spring Boot 4.1.1 · PostgreSQL 18 · Flyway · springdoc-openapi · Maven Wrapper · Git + GitHub

## Current status

**Phase 6: Transactions & Concurrency** — checkout is now **correct when things go wrong**.
Placing an order is one database transaction, so a failure anywhere in it leaves the catalogue,
the cart and the order history exactly as they were. `Product` carries a `@Version` column, which
means two people racing for the last unit can no longer both buy it: the second write is rejected,
retried up to three times, and answered with 409 if the contention does not clear. Every attempt,
successful or refused, is written to an `order_audit` table in its own transaction, so a rejection
leaves evidence even though everything else about it was rolled back.

Everything from the earlier phases still stands: a Flyway-managed schema on PostgreSQL with a
`dev`/`test` profile split, products, a single shared cart and order placement, documented by an
OpenAPI 3 spec at `/v3/api-docs` and Swagger UI at **<http://localhost:8080/swagger-ui.html>**.
The 120-test suite still runs on in-memory H2, so `./mvnw clean verify` needs nothing running. No
security and no Docker Compose yet; those arrive in Phases 8 and 10.

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
│   └── db/migration/                # V1 schema, V2 seed catalogue, V3 product category
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

On the first start **Flyway** finds an empty database, applies V1, V2 and V3 in order and records
them; the log says `Successfully applied 3 migrations`. On every start after that it finds the
schema already at version 3, says `Successfully validated 3 migrations` and applies nothing.
Hibernate then checks the schema against the entities and fails the startup if they disagree.

In a second terminal:

```bash
./mvnw clean verify             # build and run all 120 tests (no database needed)
scripts/smoke-test.sh           # 62-65 end-to-end checks against the running app
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

### Database migrations

The schema lives in Git, as ordinary SQL files:

```
src/main/resources/db/migration/
├── V1__init_schema.sql          # the five tables, their keys and indexes
├── V2__seed_products.sql        # the starting catalogue (was data.sql)
└── V3__add_product_category.sql # a new column, a backfill and an index
```

The name is a contract: `V<version>__<description>.sql`. Flyway applies the versions a database
has not seen yet, in order, each in a transaction, and writes a row per migration into a table it
owns:

```bash
docker exec -it ecomdemo-postgres psql -U ecomdemo -d ecomdemo \
  -c 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
```

**Adding a change.** Write a new file with the next version number and restart the application.
Never edit a file that has already run: Flyway stores a checksum of each applied migration and
refuses to start when one no longer matches.

```
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 1
-> Applied to database : -1645725570
-> Resolved locally    : 1764594157
```

That is the guard working, not a bug — the database that already ran the old text would otherwise
disagree, for ever, with one that runs the new text. Correct a mistake with a *new* migration.

**Making changes safe to deploy.** V3 adds `category` as a **nullable** column on purpose. During
a rolling deploy the new schema is live while instances of the old version are still inserting
products with no idea the column exists; a `NOT NULL` column with no default would fail every one
of those inserts. Adding it nullable first, backfilling, and only tightening it once every
instance writes the column is the expand/contract pattern — and the reason migrations come in
small steps rather than one large `ALTER`.

**Two kinds of migration.** These three are *versioned*: applied once, in order, never again.
Flyway also has *repeatable* migrations (`R__*.sql`), re-applied whenever their checksum changes —
the right tool for views, functions and stored procedures, which can simply be redefined. None are
needed yet.

**Why `ddl-auto=update` had to go.** It asks Hibernate to diff the entities against whatever
database it is pointed at and run the `ALTER`s it invents, unreviewed. It only ever adds, so a
renamed field leaves the old column behind still holding the real data; it cannot move data; it
runs different statements in every environment; and it records nothing, so nobody can say what it
did or undo it. `validate` turns that guesswork into a check that fails fast, and the `ALTER`s
become reviewed files in Git.

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

\dt                              -- five tables, plus flyway_schema_history
\d product                       -- the columns V1 and V3 created
SELECT * FROM product;           -- the catalogue V2 seeded
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

### Transactions and the oversell race

Placing an order touches three things: stock goes down on every product, an order and its lines
are inserted, and the cart is emptied. Until Phase 6 each of those committed on its own, which
left two holes.

**The first hole is a crash in the middle.** Stock already sold, no order to show for it, and no
way to tell afterwards. `OrderPlacementService.placeOnce()` is now `@Transactional`, so the whole
sequence is one unit: it all commits, or the database is left exactly as it was found. Spring
rolls back on an unchecked exception and commits on a checked one — every failure here is a
`RuntimeException`, so the default is what we want.

**The second hole is two people at once**, and no transaction fixes it on its own:

```
Alice                              Bob
------                             ------
read stock = 1                     read stock = 1
1 >= 1, fine                       1 >= 1, fine
write stock = 0                    write stock = 0
COMMIT                             COMMIT          -> one unit, two orders
```

Neither transaction did anything illegal; they simply could not see each other. That is the
**lost update**, and no isolation level below SERIALIZABLE prevents it. `Product` therefore
carries a `@Version` column, so Hibernate writes

```sql
UPDATE product SET stock_quantity = ?, version = version + 1 WHERE id = ? AND version = ?
```

Bob's `WHERE` no longer matches — Alice raised the version — so the UPDATE changes 0 rows and
Hibernate raises `OptimisticLockException` instead of overwriting her. Nobody waited on a lock;
the loser is simply told to try again. `OrderService.place()` does try again, up to three times,
and answers **409** if the contention does not clear. 409 and not 500: nothing is broken, the
request just lost a race and repeating it is reasonable.

**Two beans, on purpose.** `@Transactional` is applied by a proxy wrapped around the bean, and a
call from one method of a class to another method of the *same* class never crosses that proxy —
`this.placeOnce()` goes straight to the target object and the annotation is silently ignored.
That is the **self-invocation pitfall**, and it is why the retry loop (`OrderService`) and the
unit of work (`OrderPlacementService`) are separate beans. It is not a style choice: a
self-invoked `placeOnce()` would run with no transaction at all while looking perfectly correct.
Retrying also *needs* a new transaction each attempt — once a flush has failed the persistence
context is unusable and the transaction is already marked rollback-only.

**The audit is the exception that proves the rule.** A rejected checkout rolls back, so an audit
row written by that same transaction would vanish along with the failure — the one case anyone
wants a record of. `OrderAuditService.record(...)` is `Propagation.REQUIRES_NEW`: it suspends the
caller's transaction, opens a second one, commits it, and resumes the first. The row is already
durable when the caller rolls back. That independence is also why `order_audit` has **no foreign
key** to `orders`: a PLACED row names an order whose INSERT has not committed yet, and a REJECTED
row names no order at all.

Try it. The `--parallel-immediate` matters — two separate `curl` processes start about 10 ms
apart, while a checkout takes about 5, so they would not actually overlap:

```bash
# a product with exactly one unit, in the cart
ID=$(curl -s -X POST http://localhost:8080/api/products -H 'Content-Type: application/json' \
  -d '{"name":"Last One","description":"only one in stock","price":25.00,"stockQuantity":1}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -s -X POST http://localhost:8080/api/cart/items -H 'Content-Type: application/json' \
  -d "{\"productId\":$ID,\"quantity\":1}" > /dev/null

# two checkouts at once -> one 201 and one 409, never two 201s
curl -s --parallel --parallel-immediate -X POST -o /dev/null -o /dev/null -w '%{http_code}\n' \
  http://localhost:8080/api/orders http://localhost:8080/api/orders

curl -s http://localhost:8080/api/products/$ID      # stockQuantity is 0, not -1
```

The application log shows the loser being rejected, with the statement that did it:

```
WARN  c.ecomdemo.order.OrderService : Checkout attempt 1 of 3 lost an optimistic lock:
Unexpected row count (expected row count 1 but was 0)
[update product set ... version=? where id=? and version=?]
```

And the audit trail shows both halves of the race:

```bash
docker exec -it ecomdemo-postgres psql -U ecomdemo -d ecomdemo \
  -c "SELECT id, order_id, outcome, detail FROM order_audit ORDER BY id DESC LIMIT 2;"
```

**Why not lock the row instead?** `SELECT ... FOR UPDATE` — pessimistic locking — would also
prevent the oversell, by making every checkout of a popular product queue behind one row lock.
Optimistic locking takes no lock and makes nobody wait; it only costs something when a conflict
actually happens, which on a catalogue browsed far more often than it is sold from is rare. That
is the trade, and the retry budget is what makes it honest.

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

`./mvnw clean verify` runs all 120 tests in about nine seconds, against in-memory H2 — no
PostgreSQL needed. They sit at five levels, each loading only what it needs:

| Level | Annotation | What it loads | Classes |
|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | Nothing — plain objects with mocked collaborators | `ProductServiceTest`, `CartServiceTest`, `OrderServiceTest`, `OrderPlacementServiceTest` |
| Web slice | `@WebMvcTest` | The controller, JSON conversion, validation and the error handler; services are `@MockitoBean` | `ProductControllerTest`, `CartControllerTest`, `OrderControllerTest` |
| Persistence slice | `@DataJpaTest` | JPA and its own throwaway H2 database; no web layer, no Flyway | `CartRepositoryTest`, `OrderRepositoryTest` |
| Full context | `@SpringBootTest` | The whole application, on a schema Flyway migrated | `PlaceOrderFlowTest`, `OpenApiDocumentationTest`, `FlywayMigrationTest`, `ConcurrentCheckoutTest` |
| Configuration | `ApplicationContextRunner` | Only the properties files, resolved as at startup | `DatasourceConfigurationTest` |

The suite runs the **same migrations the application does**, then has Hibernate validate the
result, so a migration that drifts from the entities fails the build rather than the next deploy.
The two `@DataJpaTest` slices are the exception: `@DataJpaTest` does not run Flyway, and those
tests want empty tables, so they let Hibernate build a throwaway schema instead.

That shape is the **test pyramid**: many fast tests where the logic lives, fewer slow ones as more
of the framework is loaded. A failing unit test can only mean the service is wrong; a failing
`@SpringBootTest` could mean anything, which is why there is a handful of them and not eighty.

One test is deliberately at the top of that pyramid and could not be anywhere else.
`ConcurrentCheckoutTest` starts real threads and races two checkouts through a `CountDownLatch`,
because the bug it guards against is not in any single method — every step of `placeOnce()` is
correct on its own. It exists only in the interleaving, so proving it is gone means running the
interleaving. For the same reason it is **not** `@Transactional`: a test transaction would wrap
both threads' work in one unit and roll it back at the end, and the two checkouts would never
commit against each other. It cleans up after itself instead.

A unit test cannot check a transaction at all. `@Transactional` is applied by a proxy, and a
service built with `new` in a Mockito test has no proxy around it — so in `OrderPlacementServiceTest`
the annotation does exactly nothing. Atomicity is only ever proven against a real context and a
real database.

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
./mvnw test -Dtest=ConcurrentCheckoutTest    # the race, on its own
```

## Known gaps (closed by later phases)

- **The migrations are only ever tested on H2.** The suite runs V1–V4 against H2 in PostgreSQL
  mode, which catches drift between the migrations and the entities but not PostgreSQL-specific
  SQL — and it races two checkouts against H2's locking, not PostgreSQL's. **Phase 7** runs both
  against a real PostgreSQL container.
- **There is still one cart for the whole world.** Two "simultaneous checkouts" therefore means
  two people checking out the *same* cart, which is a strange thing to want. The locking they
  exercise is not strange at all — it is the same mechanism that protects the catalogue once
  **Phase 8** gives each user a cart of their own.
- **Nothing rate-limits a stampede.** Three retries then 409 is the right answer for a momentary
  collision; under sustained contention every attempt still costs a transaction. Backoff and
  bulkheads arrive with **Phase 22**.
- **Nothing rolls a migration back.** Flyway's community edition has no `undo`, so a bad
  migration is corrected by writing the next one. That is the normal production answer; it is
  worth knowing it is the *only* answer here.
- **No authentication.** Every endpoint is open and there is one cart for the whole world.
  **Phases 8 and 9** add Spring Security and JWT.
- **No coverage report.** The suite is broad but nothing measures or enforces how much of the
  code it reaches. **Phase 12** adds JaCoCo and SonarQube.
- **Tests run against H2, not the real database.** H2 accepts some SQL PostgreSQL would reject,
  so a green suite is not yet proof the queries work in production. **Phase 7** adds
  Testcontainers.
