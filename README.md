# EcomDemo

A learning project: an e-commerce application that evolves from a simple Spring Boot monolith into a
production-grade distributed system, **one technology per phase**. Each phase introduces exactly one
new technology, on its own feature branch, merged into `main` through a reviewed Pull Request.

**Stack:** Java 21 · Spring Boot 4.1.1 · PostgreSQL 18 · Flyway · Spring Security · springdoc-openapi · Maven Wrapper · Git + GitHub

## Current status

**Phase 8: Spring Security** — the application now knows who is calling. There are **accounts**:
you register one, the password is hashed with BCrypt and never stored, and every request carries
HTTP Basic credentials that the filter chain checks before a controller is reached. Browsing the
catalogue is still open to anyone — it is the shop window — but **changing** it needs an ADMIN,
and the cart and orders need a CUSTOMER.

The bigger change is that data now belongs to somebody. The single shared cart is gone: every
account has its own, and every order records who placed it, so asking for a stranger's order by
id is a 403 rather than a browsing opportunity. 401 and 403 come back in the same
`{"status": ..., "message": ...}` shape as every other failure.

Everything from the earlier phases still stands: a Flyway-managed schema on PostgreSQL (now at
V6), an atomic checkout protected by optimistic locking, and an OpenAPI 3 document at
`/v3/api-docs` with Swagger UI at **<http://localhost:8080/swagger-ui.html>** — which now has an
**Authorize** button, so "Try it out" can log in. 186 tests in total (163 unit and slice, 23
integration), and the smoke test has grown to 110 checks. The credentials still travel as
Base64 in a header, which is fine on localhost and nowhere else; Phase 9 replaces Basic with
signed JWTs.

## Roadmap

The full 32-phase plan, with a progress tracker, lives in **[docs/ROADMAP.md](docs/ROADMAP.md)**.

## Repository layout

```
ecomdemo/
├── src/main/java/com/ecomdemo/
│   ├── common/    # ApiError + @RestControllerAdvice shared by every feature
│   ├── security/  # the filter chain, UserDetails, CurrentUser, the 401/403 handlers
│   ├── customer/  # accounts: registration, roles, the profile
│   ├── product/   # catalogue CRUD
│   ├── cart/      # one cart per account
│   └── order/     # checkout and order history, owned by the account that placed it
├── src/main/resources/
│   ├── application.properties       # shared by every profile
│   ├── application-dev.properties   # PostgreSQL + Hikari (the default profile)
│   └── db/migration/                # V1 schema, V2 seed, V3 category, V4 version + audit,
│                                     # V5 users + seeded admin, V6 cart/orders per user
├── src/test/java/com/ecomdemo/
│   ├── support/   # TestData builders, PostgresContainerConfig, the IntegrationTest base class,
│   │               # WithSecurityRules (imports the real rules into a slice), TestAuthentication
│   └── <feature>/ # *ServiceTest, *ControllerTest, *RepositoryTest and *ApiIT per feature
├── src/test/resources/
│   ├── application-test.properties  # in-memory H2, for the fast suite (Surefire)
│   └── application-it.properties    # the container's PostgreSQL, for the *IT tests (Failsafe)
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
Docker is also what `./mvnw verify` starts the integration-test database with, so keep Docker
Desktop running while you work; `./mvnw test` on its own needs neither.

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
├── V1__init_schema.sql                          # the five tables, their keys and indexes
├── V2__seed_products.sql                        # the starting catalogue (was data.sql)
├── V3__add_product_category.sql                 # a new column, a backfill and an index
├── V4__add_product_version_and_order_audit.sql  # the optimistic lock and the audit table
├── V5__add_users.sql                            # accounts, and the seeded administrator
└── V6__cart_and_orders_per_user.sql             # cart.user_id, orders.user_id
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

**Migrating data, not just schema.** V6 is the clearest example so far of a migration that has to
think about rows it will find. `orders` may already have some, and `user_id` must end up NOT NULL,
so it goes in three steps: add the column nullable (always succeeds), give the existing rows a
value, then tighten it. On a fresh database — the test suite, a new deployment — steps two and
three are no-ops over zero rows and the result is identical. A migration has to be correct on
both. The same file takes the opposite decision about carts: a cart is scratch state with no
correct owner to attribute it to, so the old shared rows are simply deleted, while orders are
financial records and are kept.

**Two kinds of migration.** These six are *versioned*: applied once, in order, never again.
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
# create a product, restart the app, and it is still there.
# Creating one is an ADMIN's job now, hence -u.
curl -X POST http://localhost:8080/api/products -u admin:admin123 -H 'Content-Type: application/json' \
  -d '{"name":"Survivor","description":"still here after a restart","price":10.00,"stockQuantity":1}'
```

Or look at the rows directly. The H2 console is gone; point **DBeaver** or **pgAdmin** at
`localhost:5432`, database `ecomdemo`, user `ecomdemo`, password `ecomdemo` — or use `psql` in the
container:

```bash
docker exec -it ecomdemo-postgres psql -U ecomdemo -d ecomdemo

\dt                              -- the tables, plus flyway_schema_history
\d product                       -- the columns V1, V3 and V4 created
SELECT * FROM product;           -- the catalogue V2 seeded
SELECT username, role FROM users;  -- V5's accounts. `password` holds hashes, never passwords.
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
# Register a shopper first if you have not already (see "Accounts and logging in").
ASHA='-u asha:correct-horse-battery-staple'

# a product with exactly one unit, in ASHA's cart. Creating it is the admin's job.
ID=$(curl -s -X POST http://localhost:8080/api/products -u admin:admin123 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Last One","description":"only one in stock","price":25.00,"stockQuantity":1}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -s $ASHA -X POST http://localhost:8080/api/cart/items -H 'Content-Type: application/json' \
  -d "{\"productId\":$ID,\"quantity\":1}" > /dev/null

# two checkouts at once -> one 201 and one 409, never two 201s.
# Both requests are ONE shopper clicking twice: since Phase 8 two accounts have two separate
# carts, so two different people could not race over the same cart at all.
curl -s --parallel --parallel-immediate -X POST $ASHA -o /dev/null -o /dev/null -w '%{http_code}\n' \
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

| Method | Path | Who may call it | Purpose |
|---|---|---|---|
| `POST` | `/api/customers/register` | anyone | Create a CUSTOMER account (201 + `Location`) |
| `GET` | `/api/customers/me` | any account | Your own profile |
| `PUT` | `/api/customers/me` | any account | Change your own display name |
| `GET` | `/api/products` | anyone | List the catalogue |
| `GET` | `/api/products/{id}` | anyone | One product |
| `POST` | `/api/products` | **ADMIN** | Create a product (201 + `Location`) |
| `PUT` | `/api/products/{id}` | **ADMIN** | Replace a product |
| `DELETE` | `/api/products/{id}` | **ADMIN** | Delete a product (204) |
| `GET` | `/api/cart` | **CUSTOMER** | Your cart with its server-calculated total |
| `POST` | `/api/cart/items` | **CUSTOMER** | Add a product, or increase an existing line |
| `PUT` | `/api/cart/items/{productId}` | **CUSTOMER** | Set the quantity of a line |
| `DELETE` | `/api/cart/items/{productId}` | **CUSTOMER** | Remove a line |
| `POST` | `/api/orders` | **CUSTOMER** | Check out your cart (201 + `Location`) |
| `GET` | `/api/orders` | **CUSTOMER** | Your own order history |
| `GET` | `/api/orders/{id}` | **CUSTOMER** | One of your own orders |

An ADMIN is refused on the cart and orders, and that is deliberate: those endpoints act on "my"
cart and "my" orders, and an administrator has neither. Nothing about being an admin implies
being a customer, and quietly granting both is how a role system stops meaning anything.

Errors always come back as `{ "status": ..., "message": ... }`: **404** for a missing entity,
**400** for a request that fails validation, **409** for a valid request that conflicts with the
current state (empty cart, not enough stock, a username already taken), **401** when the request
carries no credentials or the wrong ones, and **403** when the credentials are fine and the
account still may not do this.

**401 and 403 are not the same thing**, and the difference matters to a client:

- **401 Unauthorized** — despite the name, this means *unauthenticated*. The server does not know
  who you are. Sending credentials could change the answer.
- **403 Forbidden** — the server knows exactly who you are and the answer is still no. Sending
  the same credentials again will never help; only a change of role would.

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

## Accounts and logging in

Two kinds of account exist. **CUSTOMER** is what registration creates; **ADMIN** is seeded by
migration V5 and cannot be created through the API — an anonymous endpoint that accepted a role
would hand out administrator accounts to whoever asked for one.

```bash
# Register. Open to anyone: requiring an account in order to create an account is a closed loop.
curl -s -X POST localhost:8080/api/customers/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"asha","password":"correct-horse-battery-staple","fullName":"Asha Rao"}'

# From now on, every call carries the credentials. curl's -u writes the Authorization header.
curl -s -u asha:correct-horse-battery-staple localhost:8080/api/customers/me
```

The seeded administrator is **`admin` / `admin123`**. That is a documented throwaway development
credential, in the same spirit as the `ecomdemo/ecomdemo` database password this repository
defaults to, and it is only safe because this application is never exposed. **A real deployment
changes it immediately** — a published default admin password is the same thing as no password.
To change it, hash a new one and update the row:

```bash
# Any BCrypt tool will do; this uses the encoder the application itself uses.
NEW_HASH='<paste a BCrypt hash here>'
docker exec -i ecomdemo-postgres psql -U ecomdemo -d ecomdemo \
  -c "UPDATE users SET password = '$NEW_HASH' WHERE username = 'admin';"
```

The credentials travel as Base64 in a header. **Base64 is an encoding, not encryption**: anybody
who can see the request can read the password, so HTTP Basic is only acceptable over TLS or, as
here, on localhost. Phase 9 replaces it with a signed token.

In Swagger UI, click **Authorize** and enter a username and password; "Try it out" then attaches
them to every call.

## Walkthrough

```bash
ADMIN='-u admin:admin123'
ASHA='-u asha:correct-horse-battery-staple'

# 1. See what is for sale. No credentials at all: the catalogue is the shop window.
curl -s localhost:8080/api/products | head -c 400

# 2. Put two mechanical keyboards in the cart - as Asha, into Asha's own cart
curl -s $ASHA -X POST localhost:8080/api/cart/items \
  -H 'Content-Type: application/json' \
  -d '{"productId": 1, "quantity": 2}'

# 3. Look at the cart - totalAmount is computed by the server, never sent by the client
curl -s $ASHA localhost:8080/api/cart

# 4. Check out. Takes no body: it always orders the whole of YOUR cart
curl -s $ASHA -X POST localhost:8080/api/orders

# 5. Read the order back. The response now says who placed it.
curl -s $ASHA localhost:8080/api/orders/1

# 6. Stock went down by 2, and the cart is empty again
curl -s localhost:8080/api/products/1
curl -s $ASHA localhost:8080/api/cart
```

Now try the failures:

```bash
# 401 - no credentials. The server does not know whose cart to show.
curl -s -i localhost:8080/api/cart | head -1

# 403 - Asha is authenticated, and still may not change the catalogue
curl -s -i $ASHA -X POST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Nope","price":1.00,"stockQuantity":1}' | head -1

# ...but the admin may
curl -s -i $ADMIN -X POST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Yes","price":1.00,"stockQuantity":1}' | head -1

# 403 - the admin has no cart of their own, so these endpoints are not for them
curl -s -i $ADMIN localhost:8080/api/cart | head -1

# 404 - no such product
curl -s -i localhost:8080/api/products/9999 | head -1

# 400 - Bean Validation rejects quantity 0 before any of our code runs
curl -s -i $ASHA -X POST localhost:8080/api/cart/items \
  -H 'Content-Type: application/json' \
  -d '{"productId": 1, "quantity": 0}' | head -1

# 409 - the cart accepts it, checkout refuses it (product 10 has only 2 in stock)
curl -s $ASHA -X POST localhost:8080/api/cart/items \
  -H 'Content-Type: application/json' -d '{"productId": 10, "quantity": 99}'
curl -s $ASHA -X POST localhost:8080/api/orders
```

And the rule that matters most — one customer's data is not another's:

```bash
curl -s -X POST localhost:8080/api/customers/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"ben","password":"another-long-password","fullName":"Ben Cole"}'

# Ben's cart is empty, even though Asha has been shopping
curl -s -u ben:another-long-password localhost:8080/api/cart

# 403 - the order exists, it is simply not his
curl -s -i -u ben:another-long-password localhost:8080/api/orders/1 | head -1
```

## How the security works

### The filter chain

Adding `spring-boot-starter-security` puts a single servlet `Filter` in front of the whole
application before a line of our code runs, and that filter delegates to an ordered chain of
small ones. Each does one job and hands the request on: work out who is calling
(`BasicAuthenticationFilter`, reading the `Authorization` header), put the result in the
`SecurityContextHolder`, and finally decide whether this caller may have this URL
(`AuthorizationFilter`).

The consequence worth remembering: **a request the chain rejects never reaches Spring MVC.**
There is no controller, no handler method and no `@RestControllerAdvice` to run — which is why
401 and 403 cannot be produced by `GlobalExceptionHandler` like every other error, and are
written instead by an `AuthenticationEntryPoint` and an `AccessDeniedHandler`
(`security/ApiError*`). They exist so that those two statuses come back in the same shape as
everything else.

The rules are in `security/SecurityConfig`, they are **ordered, and the first match wins**:

```java
.requestMatchers(HttpMethod.POST, "/api/customers/register").permitAll()
.requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
.requestMatchers("/api/products/**").hasRole("ADMIN")
.requestMatchers("/api/cart/**", "/api/orders/**").hasRole("CUSTOMER")
.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
.anyRequest().authenticated()
```

The order is meaningful, not cosmetic: the public GET rule for products has to come first, or
`/api/products/**` would swallow the reads too. And `anyRequest().authenticated()` is the safety
net — a new endpoint added in a later phase is protected until somebody deliberately opens it.
The opposite default fails silently the first time somebody forgets a line, and nothing in a test
suite notices that a URL is readable by the world.

### Authentication vs authorization

Two different questions, answered by two different beans:

- **Authentication — "who are you?"** `AppUserDetailsService` looks the username up and returns a
  `UserDetails`; the `PasswordEncoder` then decides whether the submitted password matches the
  stored hash. Two responsibilities, two beans, so swapping the user store (LDAP, an OAuth2
  provider in Phase 9) does not touch the hashing and vice versa.
- **Authorization — "may you do this?"** The URL rules above, plus `@PreAuthorize` and
  `@PostAuthorize` on `OrderService`.

`AppUserDetails` is the adapter between the two worlds. Spring Security never touches the JPA
entity; it works with `UserDetails`. Keeping them apart means the persistence model stays free of
framework interfaces, and it is where the `ROLE_` prefix is added — `hasRole("ADMIN")` is
shorthand for `hasAuthority("ROLE_ADMIN")`, a Spring Security convention with no business meaning,
so it is never written to the database.

### Hashing, not encryption

`users.password` holds a **BCrypt hash**, and the distinction is the whole point:

- **Encryption is two-way.** Something, somewhere, holds a key that turns the ciphertext back into
  the password — so a leak of the database plus that key is a leak of every password, and people
  reuse passwords across sites.
- **Hashing is one-way.** There is no key and no inverse. Logging in re-hashes what the client
  sent and compares the two hashes; nothing in this system can ever produce the original.

BCrypt adds two things on top. It is **salted** — a random value, different per row, stored inside
the hash string — so two users with the same password get different hashes and one precomputed
rainbow table cannot attack the whole stolen table at once. And it is **deliberately slow**: the
`10` in the `$2a$10$` prefix is a cost factor, 2^10 rounds of key setup per verification, which is
what keeps brute-forcing a stolen table expensive. A general-purpose hash like SHA-256 is the
wrong tool here precisely *because* it is fast.

```
$2a$10$ + 22 characters of salt + 31 characters of hash   = 60 characters, always
└┬┘ └┬┘
 │   └─ cost factor: 2^10 rounds per verification
 └───── algorithm
```

The cost is stored in each hash, so raising it later applies to new passwords without
invalidating old ones. `matches()` reads the algorithm, cost and salt back out of the stored hash,
re-hashes the submitted password with exactly those, and compares in constant time so the
comparison itself leaks nothing through timing.

### Why CSRF is switched off

Cross-Site Request Forgery is an attack on **ambient authority**: a browser attaches its session
cookie to any request aimed at that origin, including one triggered by a form on
`evil.example.com`, so the server sees a perfectly authenticated request the user never meant to
send. The defence is a token the attacker's page cannot read and therefore cannot include.

None of that applies here. This API keeps no session and sets no cookie; the credentials arrive
in an `Authorization` header that the client must attach deliberately on every call. A cross-site
form submission simply arrives with no credentials and is answered 401. There is no ambient
authority to forge, so the token would protect nothing and would break every non-browser client.

Note what that reasoning depends on: **statelessness**. The day this application authenticates
with a cookie, CSRF protection has to come back on.

### Two layers of authorization, and why they are not redundant

`/api/orders/**` already requires a CUSTOMER at the URL level, and `OrderService.findAll()`
*also* carries `@PreAuthorize("hasRole('CUSTOMER')")`. That looks like a repetition — until the
method is called from somewhere that is not that URL: a scheduled job, a message listener, a new
controller in a later phase. A URL rule protects a URL; a method rule protects the method.
Method security travels with the code, which is why the sensitive methods carry both.

The "only your own orders" part is not an annotation at all. It is the `userId` argument to the
query: `findAllByUserIdWithItems` filters in SQL, so other people's rows never leave the database.
Filtering a full result set afterwards would mean the method had already read data the caller may
not see, and was relying on remembering to drop it.

Reading **one** order by id is the exception, and it needs `@PostAuthorize`:

```java
@PreAuthorize("hasRole('CUSTOMER')")
@PostAuthorize("returnObject.username() == authentication.name")
public OrderResponse findById(Long id) { ... }
```

`@PreAuthorize` runs *before* the call and can only see the arguments and the authentication —
and whose order it is, is in the row. `@PostAuthorize` runs after, with the return value bound to
`returnObject`, which is exactly what the check needs. The price is that the work is already done
when the answer turns out to be no, so it belongs only on a read: on a method that writes, the
write would be rolled back but side effects outside the transaction — a log line, an email, a
message on a queue — would not be.

### Who the cart belongs to

`CartService.currentCart()` is where the cart's whole access control lives, and it is worth being
clear about why one method is enough. **No endpoint takes a cart id.** There is no
`GET /api/cart/{id}` to guess at, so a caller cannot ask for someone else's cart in the first
place; and inside `currentCart()` the owner is not a parameter either — it comes from the
authenticated principal, which the client has no say over. An access-control check that cannot be
reached with the wrong argument beats one that has to be remembered in five places.

The database backs it up: `uq_cart_user` makes "one cart per account" a rule it enforces rather
than an assumption the code makes.

## Tests

There are two suites now, and one command runs both.

```bash
./mvnw test      # 163 tests, ~11 s, in-memory H2, no Docker
./mvnw verify    # those 163 PLUS 23 integration tests against a real PostgreSQL, ~32 s
```

`./mvnw test` is the inner loop: it needs nothing installed and it is what you run constantly.
`./mvnw verify` additionally starts PostgreSQL in a Docker container and runs the whole
application against it — it needs **Docker running**, and it is what has to be green before a
commit is pushed.

The fast suite sits at five levels, each loading only what it needs:

| Level | Annotation | What it loads | Classes |
|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | Nothing — plain objects with mocked collaborators | `ProductServiceTest`, `CartServiceTest`, `OrderServiceTest`, `OrderPlacementServiceTest`, `CustomerServiceTest`, `AppUserDetailsServiceTest` |
| Web slice | `@WebMvcTest` | The controller, JSON conversion, validation, the error handler **and the real security rules**; services are `@MockitoBean` | `ProductControllerTest`, `CartControllerTest`, `OrderControllerTest`, `CustomerControllerTest` |
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

### Integration tests: the real database, in a container

Everything above runs on H2. H2 in `MODE=PostgreSQL` is a good imitation, and an imitation is
still not the thing: it parses and stores in its own way, its locking is its own, and a migration
or a query it happily accepts can still fail on the real server. So since Phase 7 there is a
second suite that runs the same application against **PostgreSQL itself**.

| Class | Tests | Covers |
|---|---|---|
| `ProductApiIT` | 8 | The catalogue over HTTP: the seeded rows, a create/read round trip, update, delete, 404, 400 — plus reads being public, writes needing an ADMIN, and a wrong password being indistinguishable from an unknown username |
| `CartApiIT` | 8 | Add, merge, totals across lines, update, remove, 404, 400 — each read back with a second request, so only committed state counts — plus 401/403 on the endpoints and two shoppers having two separate carts |
| `OrderApiIT` | 7 | Checkout end to end, a 409 that rolls back everything, an empty cart, two simultaneous checkouts for the last unit, 401/403, the order recording who placed it, and one customer being refused another's order |

Since Phase 8 these tests send **real credentials**: an `Authorization: Basic` header verified
against the BCrypt hash migration V5 put into the container's database, by the real filter chain.
That is the only layer where the rules and an actual login are exercised together —
`@WithMockUser` in the slices skips the authentication it would otherwise be testing.

Three pieces make it work, and each replaces something you would otherwise write by hand.

**Testcontainers starts the database.** `PostgresContainerConfig` declares the container as an
ordinary Spring `@Bean`, pinned to `postgres:18-alpine` — the same tag the `docker run` above
uses:

```java
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }
}
```

**`@ServiceConnection` wires it up.** The container comes up on a random free port, and that one
annotation reads the url, username and password off it and hands them to the auto-configured
`DataSource`. There is no JDBC url anywhere in the test sources, and `application-it.properties`
deliberately sets none — if it did, the tests would talk to that database instead of the
container, most likely your own.

**Spring owns the lifecycle, which is also what makes it fast.** Spring starts the container with
the context and stops it when the context closes, and it caches a context by the annotations that
define it. Every `*IT` extends one base class, `IntegrationTest`, so all three ask for the same
context: **one** container for the whole run. The first class pays about twelve seconds for the
context and the database; the other two take a tenth of a second each. (Add a `@MockitoBean` or a
stray `@TestPropertySource` to one subclass and it quietly gets a context — and a container — of
its own.)

The suites are split by **file name**. Maven's Surefire plugin runs `*Test.java` at the `test`
phase; its sibling Failsafe runs `*IT.java` after packaging. Failsafe deliberately does not fail
the build when a test fails — it records the result, lets the build reach
`post-integration-test` so containers are always torn down, and a separate `verify` goal then
fails the build. Renaming a class from `FooTest` to `FooIT` is the whole mechanism for moving it
between suites.

What the integration tests buy, concretely: the migrations V1–V6 are applied to an empty
**PostgreSQL 18** on every build, `NUMERIC(10,2)` rounds the way the real column rounds, and the
oversell race is settled by PostgreSQL's own row locking rather than H2's. That last one used to
be provable only by running the smoke test by hand.

```bash
./mvnw verify                                 # everything
./mvnw failsafe:integration-test -Dit.test=OrderApiIT   # one IT class (needs a prior build)
./mvnw test                                   # skip the containers entirely
```

H2 did not go away, and that is a choice rather than an oversight: the point of a pyramid is that
the fast tests stay fast. If every test needed Docker, the suite you run fifty times a day would
cost a container start each time.

## Known gaps (closed by later phases)

- **The fast suite is still only ever run on H2.** `./mvnw verify` runs the migrations and the
  checkout race against a real PostgreSQL container, so the gap is covered — but only by the 23
  integration tests. The other 163 still run on H2, so a PostgreSQL-specific problem in a code
  path no `*IT` exercises would still reach production.
- **HTTP Basic re-authenticates on every request.** That means a BCrypt verification per call —
  deliberately expensive work, paid over and over. There is also no way to log out and no way to
  revoke access short of changing a password. **Phase 9** replaces Basic with a signed JWT, which
  is verified with a signature check instead.
- **Passwords cannot be changed through the API.** A password change needs rules of its own
  (re-authenticate, re-encode, invalidate sessions) rather than riding along with a profile edit,
  so `PUT /api/customers/me` deliberately only takes a display name. The seeded admin's password
  is changed with SQL, as shown above.
- **A 403 on somebody else's order admits that it exists.** Answering 404 instead would tell the
  caller nothing, and for something more sensitive than an order that is the right trade. It is a
  judgement call, made consciously: see `OrderService.findById`.
- **There is no account lockout and no rate limit on login attempts.** BCrypt's cost makes a
  brute-force attempt expensive, which is not the same as making it impossible.
- **Nothing rate-limits a stampede.** Three retries then 409 is the right answer for a momentary
  collision; under sustained contention every attempt still costs a transaction. Backoff and
  bulkheads arrive with **Phase 22**.
- **Nothing rolls a migration back.** Flyway's community edition has no `undo`, so a bad
  migration is corrected by writing the next one. That is the normal production answer; it is
  worth knowing it is the *only* answer here.
- **No token authentication.** Credentials are sent on every request as Base64, which is safe
  only on localhost or behind TLS. **Phase 9** adds JWT.
- **No coverage report.** The suite is broad but nothing measures or enforces how much of the
  code it reaches. **Phase 12** adds JaCoCo and SonarQube.
- **The build now needs Docker.** `./mvnw verify` starts a container, so a machine without
  Docker can only run `./mvnw test`. That is the deliberate trade for testing against the real
  engine, and **Phase 11** is where CI has to be given a Docker daemon of its own.
