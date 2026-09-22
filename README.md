# EcomDemo

A learning project: an e-commerce application that evolves from a simple Spring Boot monolith into a
production-grade distributed system, **one technology per phase**. Each phase introduces exactly one
new technology, on its own feature branch, merged into `main` through a reviewed Pull Request.

**Stack:** Java 21 · Spring Boot 4.1.1 · PostgreSQL 18 · Redis · Flyway · Spring Security (JWT) · springdoc-openapi · Docker + Compose · GitHub Actions · SonarQube + JaCoCo · Maven Wrapper · Git + GitHub

## Current status

**Phase 14: Batch Processing** — the application can now do work that is not a request. Spring
Batch adds two jobs: an ADMIN uploads a product CSV and it is imported in chunks, with invalid
rows skipped up to a limit and written to an error file; and every night at 02:00 a job writes a
sales report for the previous day.

What makes this more than a loop is that **every run is written down**. A failed import can be
restarted and resumes from the last committed chunk rather than from row one; a job that has
already completed for a given input will not run again. Both facts live in six `BATCH_*` tables,
which is also where the phase's real lesson came from: Spring Batch 6 defaults to an *in-memory*
JobRepository, under which all of that appears to work and none of it survives a restart. See
[Batch processing](#batch-processing).

<details>
<summary>Phase 13: Caching</summary>

The catalogue is served from Redis. A repeated read of a product
never reaches PostgreSQL; an edit refreshes the entry in place; a delete removes it. Everything
has a TTL, because explicit eviction handles the changes this application makes and the TTL
handles the ones it does not.

The interesting half is what is **not** cached. `requireProduct` — the method the cart and
checkout use — is deliberately left alone, because it returns the live entity. A cached
`stockQuantity` would be stale; a cached `version` would defeat the optimistic locking that Phase
6 added, and the oversell bug would come back in a form the race test could not detect. The rule:
**cache what is read often and changes rarely; never cache what a decision is made against.**

Stopping Redis does not stop the shop — a cache error logs a warning and falls through to the
database, which is verified by actually stopping it.

</details>

<details>
<summary>Phase 12: Code Quality</summary>

The project is measured rather than assumed. JaCoCo covers
**both** test suites (the unit run and the integration run fork separate JVMs, so each gets its
own agent and the results are merged), and SonarQube runs in its own compose stack behind a
quality gate.

```
Coverage 96.1%  ·  Bugs 0  ·  Vulnerabilities 0  ·  Code smells 0  ·  Debt 0 min
Reliability A   ·  Security A  ·  Maintainability A  ·  QUALITY GATE: OK
```

That started at 16 issues and 92 minutes of debt. One was a real bug (a `SecureRandom` rebuilt on
every call), two were confirmed by the compiler rather than taken on trust, and five were tests
whose assertions did not pin down which call was supposed to throw. The one finding that was not
"fixed" — CSRF being disabled — is a *review* rule with a deliberate answer, suppressed in the
code with its reasoning attached rather than dismissed in a server database that a rebuild wipes.

</details>

<details>
<summary>Phase 11: Continuous Integration</summary>

Every pull request is built and tested by GitHub Actions before anyone can merge it, and every
merge to `main` publishes a container image to GHCR tagged with the commit SHA.

The gate is not a claim: a deliberately failing test was committed, watched turn the run red, and
reverted — with the publish job skipped and the test reports still uploaded, which is the only
time anybody wants them. A build takes about a minute, and the Maven cache takes ~20% off that.
Dependabot watches Maven and the actions weekly.

</details>

<details>
<summary>Phase 10: Containerization</summary>

The whole system starts with one command:

```bash
cp .env.example .env && docker compose up --build
```

Two containers on a private network — the application and PostgreSQL — with the app waiting for
the database to be genuinely *ready* rather than merely started, and the data in a named volume
that outlives them both. The image is built in two stages, so the JDK, Maven and the source code
stay in the builder and only a JRE and the application are published: **410 MB**, running as a
non-root user, with a heap sized from the container's limit instead of a hard-coded number.

Nothing about the application changed. This phase is packaging — but packaging is what makes
"works on my machine" stop being a sentence anybody has to say.

</details>

<details>
<summary>Phase 9: JWT Authentication</summary>

The password is sent **once**. `POST /api/auth/login`
exchanges it for a signed, short-lived JSON Web Token, and every later call carries
`Authorization: Bearer <token>` instead. The application verifies the signature, the expiry and
the issuer on each request and reads the caller's id and roles straight out of the token — no
session, no database lookup, and no BCrypt verification per call. Measured on this machine:
about **106 ms** for a login, about **14 ms** for an authenticated request that used to cost the
same as a login.

The rules themselves did not change. Browsing the catalogue is open to anyone, changing it needs
an ADMIN, the cart and orders need a CUSTOMER, and every account sees only its own data — all of
it decided from authorities, which never cared where they came from. What changed is the first
step of the chain, and what it costs.

Everything from the earlier phases still stands: a Flyway-managed schema on PostgreSQL (V6), an
atomic checkout protected by optimistic locking, and an OpenAPI 3 document at `/v3/api-docs` with
Swagger UI at **<http://localhost:8080/swagger-ui.html>** — whose **Authorize** button now takes a
token. 220 tests in total (190 unit and slice, 30 integration), and the smoke test has grown to
125 checks. A token still cannot be revoked before it expires and there is no refresh endpoint;
both are deliberate gaps, explained under "Known gaps".

</details>

## Roadmap

The full 32-phase plan, with a progress tracker, lives in **[docs/ROADMAP.md](docs/ROADMAP.md)**.

## Repository layout

```
ecomdemo/
├── src/main/java/com/ecomdemo/
│   ├── common/    # ApiError + @RestControllerAdvice shared by every feature
│   ├── cache/     # cache names, per-cache TTL and serializers, hit/miss logging
│   ├── batch/     # the two Spring Batch jobs, the admin endpoints, the JDBC JobRepository
│   ├── security/  # the filter chain, the JWT key/encoder/decoder, CurrentUser, 401/403 handlers
│   ├── auth/      # POST /api/auth/login: exchanging a password for a signed token
│   ├── customer/  # accounts: registration, roles, the profile
│   ├── product/   # catalogue CRUD
│   ├── cart/      # one cart per account
│   └── order/     # checkout and order history, owned by the account that placed it
├── src/main/resources/
│   ├── application.properties       # shared by every profile
│   ├── application-dev.properties   # PostgreSQL + Hikari (the default profile)
│   └── db/migration/                # V1 schema, V2 seed, V3 category, V4 version + audit,
│                                     # V5 users + seeded admin, V6 cart/orders per user,
│                                     # V7 the Spring Batch tables, V8 index on product.name
├── src/test/java/com/ecomdemo/
│   ├── support/   # TestData builders, PostgresContainerConfig, the IntegrationTest base class,
│   │               # WithSecurityRules (imports the real rules into a slice), TestAuthentication
│   └── <feature>/ # *ServiceTest, *ControllerTest, *RepositoryTest and *ApiIT per feature
├── src/test/resources/
│   ├── application-test.properties  # in-memory H2, for the fast suite (Surefire)
│   └── application-it.properties    # the container's PostgreSQL, for the *IT tests (Failsafe)
├── Dockerfile     # multi-stage: JDK+Maven to build, JRE to run, non-root, layered jar
├── .dockerignore  # keeps target/, .git and .env out of the build context
├── compose.yaml   # the app + PostgreSQL + Redis: health checks, named volumes, one network
├── compose.sonar.yaml  # SonarQube + its own PostgreSQL, started only for an analysis
├── .env.example   # every variable, documented; .env itself is gitignored
├── docs/          # roadmap, phase specs, process docs, decisions, progress
├── scripts/       # smoke-test.sh, sonar-setup.sh (the quality gate as code)
└── .github/
    ├── workflows/ci.yml   # build + test every PR; publish the image on merge to main
    ├── dependabot.yml     # weekly Maven and Actions updates
    └── pull_request_template.md
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

Two ways. **Docker Compose** is the one to use — it needs nothing on your machine but Docker, and
it runs the same image everywhere. Running it from source still works and is the faster inner
loop when you are editing code.

### The whole system, in one command

```bash
cp .env.example .env            # then put a real JWT_SECRET in it
docker compose up --build       # add -d to detach
```

That builds the application image and starts two containers — `ecomdemo-app` and `ecomdemo-db` —
on a private network. The application waits for PostgreSQL to be genuinely *ready*, not merely
started, applies the migrations, and comes up on <http://localhost:8080>.

```bash
docker compose ps               # SERVICE  STATUS: both should say (healthy)
docker compose logs -f app      # follow the application log
docker compose down             # stop and remove the containers, KEEPING the database
docker compose down -v          # ...and delete the database volume too
```

`docker compose down` is not destructive: the database lives in a named volume that outlives the
containers, so `up` again finds the schema already at V6. Only `-v` throws it away.

**`.env` is gitignored**; `.env.example` is the documented template. Every value has a working
default, so an empty `.env` starts a usable stack — but set `JWT_SECRET` (at least 32 characters;
`openssl rand -base64 48` will do), or every restart invalidates every token that was issued.

### Or from source

Requires **JDK 21** on the path, and Docker for the database. Docker is also what `./mvnw verify`
starts the integration-test database with, so keep Docker Desktop running while you work;
`./mvnw test` on its own needs neither.

Start PostgreSQL on its own — stop the compose stack first if it is up, or the two will fight over
port 5432:

```bash
docker run --name ecomdemo-postgres \
  -e POSTGRES_DB=ecomdemo -e POSTGRES_USER=ecomdemo -e POSTGRES_PASSWORD=ecomdemo \
  -p 5432:5432 -d postgres:18-alpine

docker start ecomdemo-postgres          # after the first time: bring it back up
docker stop  ecomdemo-postgres          # shut it down, data kept
docker rm -f ecomdemo-postgres          # delete it AND its data, to start clean
```

Then:

```bash
JWT_SECRET='at-least-32-characters-of-random-text' ./mvnw spring-boot:run
```

On the first start **Flyway** finds an empty database, applies V1–V6 in order and records them;
the log says `Successfully applied 6 migrations`. On every start after that it finds the schema
already at version 6, says `Successfully validated 6 migrations` and applies nothing. Hibernate
then checks the schema against the entities and fails the startup if they disagree.

In a second terminal, against either way of running it:

```bash
./mvnw clean verify             # build and run all 303 tests (needs Docker for the 62 *IT)
scripts/smoke-test.sh           # 156 end-to-end checks against the running app
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
docker exec -it ecomdemo-db psql -U ecomdemo -d ecomdemo \
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
container. The container is `ecomdemo-db` when you are running the compose stack, and
`ecomdemo-postgres` when you started PostgreSQL by hand:

```bash
docker exec -it ecomdemo-db psql -U ecomdemo -d ecomdemo

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
docker exec -it ecomdemo-db psql -U ecomdemo -d ecomdemo \
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
| `POST` | `/api/auth/login` | anyone | Exchange a password for a signed token |
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
| `POST` | `/api/admin/batch/product-import` | **ADMIN** | Import a product CSV (`multipart/form-data`, part `file`) |
| `GET` | `/api/admin/batch/executions/{id}` | **ADMIN** | Look one job run up in the JobRepository |
| `POST` | `/api/admin/batch/executions/{id}/restart` | **ADMIN** | Restart a failed import from where it stopped |

A `200` from the two `POST`s means the **job ran**, not that it succeeded — the body's `status`
is the outcome, and a `FAILED` job comes back as a 200 with a failure message. Collapsing the two
would leave nowhere to report the common case: a run that completed with rows skipped.

An ADMIN is refused on the cart and orders, and that is deliberate: those endpoints act on "my"
cart and "my" orders, and an administrator has neither. Nothing about being an admin implies
being a customer, and quietly granting both is how a role system stops meaning anything.

Errors always come back as `{ "status": ..., "message": ... }`: **404** for a missing entity,
**400** for a request that fails validation, **409** for a valid request that conflicts with the
current state (empty cart, not enough stock, a username already taken), **401** when the request
carries no token, a token that does not verify, or wrong credentials at login, and **403** when
the token is fine and the account still may not do this.

**401 and 403 are not the same thing**, and the difference matters to a client:

- **401 Unauthorized** — despite the name, this means *unauthenticated*. The server does not know
  who you are. Presenting a valid token could change the answer. A **tampered or expired token is
  also a 401**, not a 403: it never became an identity at all, so there was nobody to forbid.
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
# 1. Register. Open to anyone: requiring an account in order to create an account is a closed loop.
curl -s -X POST localhost:8080/api/customers/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"asha","password":"correct-horse-battery-staple","fullName":"Asha Rao"}'

# 2. Log in. This is the ONLY request that carries a password.
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"asha","password":"correct-horse-battery-staple"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')

# 3. From now on, every call carries the token instead.
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/customers/me
```

Look inside the token — no key required, which is the point:

```bash
python3 -c "
import base64, json, sys
p = sys.argv[1].split('.')[1]; p += '=' * (-len(p) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(p)), indent=1))" "$TOKEN"
```

```json
{"iss": "ecomdemo", "sub": "asha", "uid": 4, "exp": 1790046372, "iat": 1790045472,
 "roles": ["CUSTOMER"]}
```

That payload is **encoded, not encrypted**. Anyone holding the token can read it, so nothing
secret may ever go into a claim — the signature is what makes the claims trustworthy, not
secrecy. Edit a single character and the signature stops matching, which is why `"roles":
["ADMIN"]` cannot simply be typed in.

The seeded administrator is **`admin` / `admin123`**. That is a documented throwaway development
credential, in the same spirit as the `ecomdemo/ecomdemo` database password this repository
defaults to, and it is only safe because this application is never exposed. **A real deployment
changes it immediately** — a published default admin password is the same thing as no password.
To change it, hash a new one and update the row:

```bash
# Any BCrypt tool will do; this uses the encoder the application itself uses.
NEW_HASH='<paste a BCrypt hash here>'
docker exec -i ecomdemo-db psql -U ecomdemo -d ecomdemo \
  -c "UPDATE users SET password = '$NEW_HASH' WHERE username = 'admin';"
```

### The signing key

Tokens are signed with HMAC-SHA256, and the key comes from the environment:

```bash
JWT_SECRET='at-least-32-characters-of-random-text' ./mvnw spring-boot:run
```

There is deliberately **no default in the repository**. A signing key committed to Git is a
signing key everybody has, and anyone holding it can mint a token for any account with any role —
it is a far worse thing to leak than a password, because it needs no account at all.

If `JWT_SECRET` is unset the application generates a random key at startup and says so, loudly:

```
WARN JwtConfig : JWT_SECRET is not set, so a random signing key was generated for this run.
Logins work, but EVERY TOKEN BECOMES INVALID WHEN THIS APPLICATION RESTARTS...
```

Everything works, there is no setup step, and nothing secret is in Git — but tokens do not
survive a restart, and two instances would reject each other's. That is the right trade for a
learning project and the wrong one for anything else, which is why it warns rather than
proceeding quietly. A key shorter than 32 bytes is **refused at startup** rather than padded:
HS256 needs 256 bits, and silently weakening a signature is worse than failing to start.

### "Bearer" is meant literally

Whoever holds the token is the account. There is nothing else to check — no password, no second
factor — so a token is as sensitive as a password, and like HTTP Basic before it, it is only safe
over TLS or, as here, on localhost.

In Swagger UI, call `POST /api/auth/login`, copy the `accessToken`, then click **Authorize** and
paste it in; "Try it out" attaches it to every call after that.

## Walkthrough

```bash
# Log in once each, and keep the tokens in a variable.
login() { curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])'; }

ASHA_TOKEN="$(login asha correct-horse-battery-staple)"
ASHA=(-H "Authorization: Bearer $ASHA_TOKEN")
ADMIN=(-H "Authorization: Bearer $(login admin admin123)")

# 1. See what is for sale. No token at all: the catalogue is the shop window.
curl -s localhost:8080/api/products | head -c 400

# 2. Put two mechanical keyboards in the cart - as Asha, into Asha's own cart
curl -s "${ASHA[@]}" -X POST localhost:8080/api/cart/items \
  -H 'Content-Type: application/json' \
  -d '{"productId": 1, "quantity": 2}'

# 3. Look at the cart - totalAmount is computed by the server, never sent by the client
curl -s "${ASHA[@]}" localhost:8080/api/cart

# 4. Check out. Takes no body: it always orders the whole of YOUR cart
curl -s "${ASHA[@]}" -X POST localhost:8080/api/orders

# 5. Read the order back. The response says who placed it.
curl -s "${ASHA[@]}" localhost:8080/api/orders/1

# 6. Stock went down by 2, and the cart is empty again
curl -s localhost:8080/api/products/1
curl -s "${ASHA[@]}" localhost:8080/api/cart
```

Now try the failures:

```bash
# 401 - no token. The server does not know whose cart to show.
curl -s -i localhost:8080/api/cart | head -1

# 401 - a token that has been edited. The signature no longer covers the payload, so it never
# becomes an identity at all: this is 401, not 403.
curl -s -i -H "Authorization: Bearer ${ASHA_TOKEN}tampered" localhost:8080/api/cart | head -1

# 403 - Asha is authenticated, and still may not change the catalogue
curl -s -i "${ASHA[@]}" -X POST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Nope","price":1.00,"stockQuantity":1}' | head -1

# ...but the admin may
curl -s -i "${ADMIN[@]}" -X POST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Yes","price":1.00,"stockQuantity":1}' | head -1

# 403 - the admin has no cart of their own, so these endpoints are not for them
curl -s -i "${ADMIN[@]}" localhost:8080/api/cart | head -1

# 404 - no such product
curl -s -i localhost:8080/api/products/9999 | head -1

# 400 - Bean Validation rejects quantity 0 before any of our code runs
curl -s -i "${ASHA[@]}" -X POST localhost:8080/api/cart/items \
  -H 'Content-Type: application/json' \
  -d '{"productId": 1, "quantity": 0}' | head -1

# 409 - the cart accepts it, checkout refuses it (product 10 has only 2 in stock)
curl -s "${ASHA[@]}" -X POST localhost:8080/api/cart/items \
  -H 'Content-Type: application/json' -d '{"productId": 10, "quantity": 99}'
curl -s "${ASHA[@]}" -X POST localhost:8080/api/orders
```

And the rule that matters most — one customer's data is not another's:

```bash
curl -s -X POST localhost:8080/api/customers/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"ben","password":"another-long-password","fullName":"Ben Cole"}'
BEN=(-H "Authorization: Bearer $(login ben another-long-password)")

# Ben's cart is empty, even though Asha has been shopping
curl -s "${BEN[@]}" localhost:8080/api/cart

# 403 - the order exists, it is simply not his
curl -s -i "${BEN[@]}" localhost:8080/api/orders/1 | head -1
```

## Caching

```bash
docker compose up -d                       # brings up `cache` alongside `app` and `db`
curl -s localhost:8080/api/products/1      # miss: reads PostgreSQL, populates Redis
curl -s localhost:8080/api/products/1      # hit:  never touches PostgreSQL

docker exec ecomdemo-cache redis-cli KEYS '*'
docker exec ecomdemo-cache redis-cli GET 'product::1'
docker exec ecomdemo-cache redis-cli TTL 'product::1'
```

Turn the commentary on to watch it work:

```
logging.level.com.ecomdemo.cache=DEBUG
```

```
cache MISS product for key 1
cache PUT  product for key 1
cache HIT  product for key 1
```

### Cache-aside

`@Cacheable` implements the **cache-aside** pattern: look in the cache; on a miss, call the method
and store what it returns. The application owns the data — Redis never talks to PostgreSQL and
knows nothing about it.

That is why losing the cache costs nothing but latency, and why the Redis container has **no
volume** and runs with `--save "" --appendonly no`. Everything in it can be recomputed. Persisting
a derived copy would buy nothing and cost fork() pauses and disk.

| | Where the truth is | Losing it costs |
|---|---|---|
| Cache-aside | PostgreSQL | a slow minute |
| Write-through / write-behind | the cache, at least briefly | data |

### What is cached, and what must never be

```java
@Cacheable(cacheNames = PRODUCT,      key = "#id")   ProductResponse findById(Long id)
@Cacheable(cacheNames = PRODUCT_LIST, key = "'all'") List<ProductResponse> findAll()

// NOT cached, and this is the important one:
public Product requireProduct(Long id)               // the live entity, used by cart + checkout
```

`requireProduct` returns the **managed JPA entity**. Caching it would hand checkout a detached
object with a stale `stockQuantity` *and* a stale `version` — and that second one is the killer,
because optimistic locking compares versions. A cached version defeats the very mechanism Phase 6
added, so the oversell bug would return in a form the Phase 6 race test could not detect: both
threads would simply agree on the same wrong number.

**Cache what is read often and changes rarely; never cache what a decision is made against.**

That rule leaves one loose end, and Phase 13 accepted it: after a sale the catalogue showed the
old stock figure for up to the TTL, on the grounds that browsing is a view and checkout reads live.
It was a defensible trade and it was still wrong in practice — a shopper could read "5 in stock",
add five to a cart and be refused at checkout, with the database correct and unhelpful throughout.

**It is closed now, and the fix is entirely about timing.** `ProductService.save` publishes a
`ProductStockChangedEvent`; `ProductCacheEvictor` listens with
`@TransactionalEventListener(AFTER_COMMIT)` and drops both the product's entry and the listing. The
reason it is not simply `@CacheEvict` on the write is the reason Phase 13 deferred it: that write
runs once per line inside a transaction that may still roll back, so evicting there discards good
entries on every failed checkout — and evicts before the new row is visible, opening a window for a
concurrent reader to repopulate the cache from the pre-commit state and be wrong until the TTL.
Moving the same eviction to just after the commit answers both.

```bash
# stock 9, buy 2 — and read it straight back, with no TTL to wait for
curl -s localhost:8080/api/products/3 | jq .stockQuantity     # 9
# ...place the order...
curl -s localhost:8080/api/products/3 | jq .stockQuantity     # 7, immediately
```

Checkout still reads live through `requireProduct`, so the rule above is unchanged: the number a
sale is decided against never comes from a cache.

### Invalidation

Three annotations, doing three different things:

```java
@Cacheable  // populate on a miss
@CachePut   // run the method AND write its result into the cache
@CacheEvict // remove the entry
```

An update needs two of them at once:

```java
@Caching(
    put   = @CachePut(cacheNames = PRODUCT, key = "#id"),        // refresh this product
    evict = @CacheEvict(cacheNames = PRODUCT_LIST, key = "'all'")) // discard the listing
```

The asymmetry is cache invalidation in miniature: **refresh what you can compute, discard what you
cannot.** The method has to run anyway and its return value is exactly what the next read would
produce, so `@CachePut` saves a database round trip. The listing holds every product and there is
no way to patch one entry inside it, so it goes.

### TTL and eviction

| Cache | TTL | Why |
|---|---|---|
| `product` | 10 min | changes rarely, and every edit evicts it anyway |
| `productList` | 2 min | one key covering every product: costliest to hold, likeliest to be wrong |

**Every cache has a TTL**, and that is the real answer to invalidation. Explicit eviction handles
the changes this application makes; the TTL handles the ones it does not — a migration, a manual
`UPDATE`, a bug in an eviction rule. Without one, a single missed eviction is wrong for ever.

Redis has its own eviction, for a different problem — running out of memory:

```
--maxmemory 256mb --maxmemory-policy allkeys-lru
```

`allkeys-lru` drops the least recently used key and carries on. The default, `noeviction`, would
start *failing writes* — turning a full cache into an application error.

### Serialization: one type per cache

Each cache declares exactly what it holds:

```java
new JacksonJsonRedisSerializer<>(jsonMapper, types.constructType(ProductResponse.class))
new JacksonJsonRedisSerializer<>(jsonMapper, types.constructCollectionType(List.class, ProductResponse.class))
```

The obvious alternative — one generic serializer with Jackson **default typing**, writing the class
name into each document — was tried first and is wrong twice over:

1. **It did not round-trip.** A root-level `List` was written as a bare JSON array with no type id,
   and the reader then demanded one. Every cached listing read failed.
2. **It is a deserialization gadget.** "The document names the class to instantiate" is a
   well-trodden path to remote code execution. Jackson's own convenience method for it is called
   `enableUnsafeDefaultTyping`.

A per-cache type removes both, and the stored JSON is smaller and legible:

```json
{"id":1,"name":"Mechanical Keyboard","price":8999.00,"stockQuantity":25,"category":"PERIPHERALS"}
```

Money survives with its scale intact — `42.50`, not `42.5`. That is worth a test of its own, and
has one.

### When the cache breaks

A cache-aside cache is an optimisation over a database that can still answer, so losing it should
cost latency, not availability. Spring's default `CacheErrorHandler` rethrows, which turns a cache
problem into a failed request. This one logs and falls through:

```bash
docker compose stop cache
curl -o /dev/null -w "%{http_code}" localhost:8080/api/products    # 200
```

```
WARN CacheConfig : cache GET failed on productList for key all
                   — falling through to the database
```

Note the asymmetry: a swallowed **evict** leaves a stale entry, and only the TTL will clear it.
That is the second reason every cache here has one.

### Redis data types

This application uses exactly one: a **string** per key, holding JSON, with an expiry. Spring's
cache abstraction is a key-value map and needs nothing more.

Redis offers a good deal more — hashes for storing an object field by field, sorted sets for
leaderboards and rate limiting, streams for event logs, sets for membership. None of it is reachable
through `@Cacheable`; using it means a `RedisTemplate` and writing the access code yourself. Worth
knowing the map is not the territory.

## Batch processing

Everything before this phase happened inside an HTTP request. Batch work does not: it processes a
volume of data on a schedule or on demand, and the interesting questions are what happens when it
is half done.

Two jobs:

| Job | Trigger | What it does |
|---|---|---|
| `productImportJob` | `POST /api/admin/batch/product-import` | Reads a product CSV, validates each row, and upserts the catalogue from it |
| `salesReportJob` | `@Scheduled` cron, 02:00 daily | Writes a CSV of yesterday's order count, revenue and best sellers |

### Try it

```bash
docker compose up -d --build
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')

# A file with one bad row in it.
cat > /tmp/products.csv <<'CSV'
name,description,price,stock_quantity,category
Standing Desk,Electric, sit-stand,449.00,7,FURNITURE
Monitor Arm,Single, gas-spring,79.50,25,ACCESSORY
Broken Row,no price at all,,3,ACCESSORY
CSV

curl -s -X POST localhost:8080/api/admin/batch/product-import \
  -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/products.csv" | python3 -m json.tool
```

```json
{
  "execution": { "id": 1, "instanceId": 1, "jobName": "productImportJob",
                 "status": "COMPLETED", "readCount": 3, "writeCount": 2, "skipCount": 1,
                 "steps": [ { "name": "importProducts", "commitCount": 1, ... } ] },
  "inputFile": "/var/lib/ecomdemo/batch/uploads/1591342e-...-products.csv",
  "errorFile": "/var/lib/ecomdemo/batch/uploads/1591342e-...-products.csv.errors.csv"
}
```

Two rows in, one rejected — and the rejection is not just a number:

```bash
docker exec ecomdemo-app sh -c 'cat /var/lib/ecomdemo/batch/uploads/*.errors.csv'
```

```
line,reason,original_line
4,"line 4: price is required","Broken Row,no price at all,,3,ACCESSORY"
```

Note the second and third rows of that file: the descriptions contain commas, so the tokenizer
sees the wrong number of columns. That is *also* a skip, recorded the same way — the import does
not silently import a shifted row.

### Job, JobInstance, JobExecution

Three words that look like synonyms and are not, and almost everything else follows from the
difference:

- A **Job** is the definition — `productImportJob`, a name and a list of steps.
- A **JobInstance** is a unit of *work*: the job's name plus its **identifying parameters**. For
  the import that is the path of the file being imported; for the report, the date. "Import
  *this* file" is one instance for ever.
- A **JobExecution** is one *attempt* at an instance. A restart is a second execution against the
  same instance, which is why the response carries both ids.

An instance that has COMPLETED will not run again — the JobRepository refuses it. That is the
guarantee behind a nightly job: two application instances both firing the 02:00 cron produce one
report, not two, because the second is turned away rather than because the scheduler was clever.

### Chunks, and the transaction boundary

The import is a **chunk-oriented** step, which is one sentence worth memorising: *read n items
one at a time, process each one, hand the whole batch to the writer, commit, repeat.* The
transaction spans **process and write**; the reader sits outside it, which is how a file reader
keeps its place across a rollback.

Everything else follows from that sentence:

- The writer is handed a list, not an item, so it can issue one batched statement.
- The **chunk size is the knob that matters**. One transaction for a 10,000-row file holds locks
  for its duration and loses everything on the last row; one transaction per row pays a commit
  ten thousand times. This project uses 100 (`ecomdemo.batch.chunk-size`).
- A restart resumes at a **chunk boundary**, not at an exact row.

The other kind of step is a **tasklet**: one method, called once, in one transaction. The sales
report uses both — a tasklet for the summary (two aggregate queries and a few lines of file, no
stream to iterate) and a chunk step over a database cursor for the best-seller table. Using the
chunk machinery for the summary would be ceremony; using a tasklet for the table would not scale
past what fits in memory.

### Skips, and the limit

```
ecomdemo.batch.skip-limit=50
```

A skip limit is a statement about data quality: *a few malformed rows in a supplier's file are
normal; a file that is mostly malformed is a different file.* Cross the limit and the job fails
rather than reporting a tidy COMPLETED over three rows out of ten thousand.

Only two exception types are skippable — a line that will not parse, and a row the catalogue
refuses. That narrowness is the point. `.skip(Exception.class)` would also skip a
`NullPointerException` in the processor and a connection failure in the writer, and "skip bad
data" and "swallow bugs" look identical from a distance.

### Restart

Restart is the reason all of this is written to a database. Try it:

```bash
# 120 good rows, then 60 with an unparseable price - past the skip limit of 50.
{ echo "name,description,price,stock_quantity,category"
  for i in $(seq 1 120);  do echo "Restart Demo $i,x,9.99,5,DEMO"; done
  for i in $(seq 1 60);   do echo "Restart Demo bad $i,x,NOPE,5,DEMO"; done
} > /tmp/restart-demo.csv

curl -s -X POST localhost:8080/api/admin/batch/product-import \
  -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/restart-demo.csv" | python3 -m json.tool
```

```
status  FAILED     writeCount 100     skipCount 50
failureMessage  FatalStepExecutionException: Unable to process chunk; caused by
                SkipLimitExceededException: Skip limit of '50' exceeded; caused by
                InvalidProductRowException: line 172: price 'NOPE' is not a number
inputFile       /var/lib/ecomdemo/batch/uploads/<uuid>-restart-demo.csv
```

A hundred rows are already in the catalogue: the chunks that committed before the limit was hit
survive the failure. Now fix the file **in place** and restart:

```bash
IN=<the inputFile from above>
docker exec ecomdemo-app sh -c "sed -i 's/,NOPE,/,19.99,/' '$IN'"

curl -s -X POST localhost:8080/api/admin/batch/executions/5/restart \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

```
status COMPLETED    id 6    instanceId 5    readCount 80    writeCount 80
```

Execution **6** against instance **5** — a second attempt at the same work. It read 80 rows, not
180: the reader's saved position was restored from `BATCH_STEP_EXECUTION_CONTEXT` and it carried
on from the last commit. The 100 rows already imported were not touched.

Three things about that are worth keeping:

- **In place matters.** A restart trusts the saved LINE NUMBER. Correcting bad lines without
  changing the line count is safe; swapping in a different file that happens to share the path
  would have the reader resume at line 100 of a file it has never seen. (This is also why each
  upload is staged under a name of its own — so a *new* upload is new work, not a restart.)
- **The upsert is what makes it safe.** The rows of the chunk that was rolled back are read
  again, and processing them twice changes nothing.
- **A restart does not reconsider SKIPPED rows.** It resumes from the last *commit*, and rows
  that were read, rejected and committed past are behind that point. What a restart recovers is
  work that was never done, not work that was refused. Those rows are in the error file; the way
  to get them in is to fix the file and import it again as a new upload.

### The bug this phase nearly shipped

Spring Batch 6's default `JobRepository` is `ResourcelessJobRepository` — **in memory**. Under it
everything above appears to work: jobs run, counters are right, a completed instance is refused a
second time, a restart works within one process. And the six `BATCH_*` tables that Flyway created
stay completely empty, so none of it survives the JVM.

That removes the entire reason to use the framework rather than a loop, silently. It was caught
by an assertion about **rows in a table** rather than about behaviour, because the behaviour was
indistinguishable:

```java
assertThat(jdbc.queryForList(
    "SELECT ... FROM batch_job_execution WHERE job_execution_id = ?", execution.id()))
        .singleElement()...
```

The fix is `BatchConfig`, which extends `DefaultBatchConfiguration` — extending it is what makes
Boot's auto-configuration back off — and supplies a `JdbcJobRepositoryFactoryBean`.
`BatchJobRepositoryTest` now guards it.

### The schema

The six tables and three sequences arrive as Flyway `V7`, copied verbatim from Spring Batch's own
`schema-postgresql.sql`. They are production schema — they are where "has this already run?" is
answered — so they belong under review like any other table. Spring Boot 4 agrees by omission:
unlike Boot 3 it ships no `spring.batch.jdbc.initialize-schema` property at all, and nothing
creates them for you.

| Table | Holds |
|---|---|
| `BATCH_JOB_INSTANCE` | one row per unit of work (job name + identifying parameters) |
| `BATCH_JOB_EXECUTION` | one row per attempt; a restart adds a second against the same instance |
| `BATCH_JOB_EXECUTION_PARAMS` | each attempt's parameters, and whether each identifies the instance |
| `BATCH_STEP_EXECUTION` | per step, per attempt: read, write, skip, commit and rollback counters |
| `BATCH_*_EXECUTION_CONTEXT` | the serialized ExecutionContext — **the reader's saved position** |

`V8` adds an index on `product.name`, because the import looks every row up by name before
deciding insert-or-update and a 10,000-row file would otherwise be 10,000 sequential scans. It is
deliberately **not** unique: this API has allowed two products to share a name since Phase 1.

### Configuration

```properties
spring.batch.job.enabled=false            # do NOT run every job bean at startup
ecomdemo.batch.directory=${BATCH_DIR:./batch}
ecomdemo.batch.chunk-size=100
ecomdemo.batch.skip-limit=50
ecomdemo.batch.sales-report-cron=0 0 2 * * *
```

Two of those are worth a sentence each:

- **`spring.batch.job.enabled=false`.** Boot's `JobLauncherApplicationRunner` runs every `Job`
  bean once the context is up. Right for a batch application launched from the command line;
  wrong for a web application that happens to contain jobs, where a restart would re-import
  whatever file the last upload left on disk.
- **The cron has six fields, not five.** Spring's cron starts at SECONDS, so the familiar Unix
  `0 2 * * *` means "the 2nd minute of every hour" here. Set the property to `-` to turn the
  schedule off entirely. To watch the report job without waiting until 02:00:

  ```bash
  ECOMDEMO_BATCH_SALESREPORTCRON='0 * * * * *' docker compose up -d
  docker exec ecomdemo-app sh -c 'ls /var/lib/ecomdemo/batch/reports'
  ```

The batch directory is a named Docker volume mounted at `/var/lib/ecomdemo/batch` and created in
the Dockerfile as the runtime user. Both halves matter: `/app` is owned by root and the
application is not, and an import that failed before a container was replaced could not be
resumed if its staged input had gone with the container.

## Metrics and monitoring

Three pieces, and it is worth being clear about which does what. **Micrometer** is a facade the
application records numbers against — `counter.increment()`, `timer.record()` — and it knows
nothing about Prometheus. The **Prometheus registry** holds those numbers in memory and renders
them, on request, as text at `/actuator/prometheus`. **Prometheus** walks up to that URL every
fifteen seconds and stores what it finds. **Grafana** draws it.

The important word is *request*. Prometheus is a **pull** system: the application never connects
to it, never buffers, never retries, and cannot be slowed down by a monitoring system having a bad
day. It also means a target that goes silent is a signal — `up == 0` — rather than an absence
nobody notices, which is the failure mode of every push-based agent.

```
  OrderService                Actuator                 Prometheus            Grafana
  ─────────────               ─────────                ──────────            ───────
  counter.increment()  ──▶   in-memory registry
  timer.record()              │
                              └─ renders on demand ◀── GET /actuator/  ──▶  PromQL ──▶ panels
                                 orders_placed_total    prometheus           every 15s
                                                        (pull, every 15s)
```

### Four meter types, and when each is the right one

| Type | Answers | Here |
|---|---|---|
| **Counter** | "how many, how fast" — only ever goes up | `orders.placed` |
| **Gauge** | "what is it right now" — goes up and down, sampled | `jvm_memory_used_bytes`, `hikaricp_connections_active` (both auto-configured; this phase writes none of its own) |
| **Timer** | "how long, and how many" — a counter and a duration in one meter | `checkout.duration` |
| **Distribution summary** | "how big" — a timer for something that is not time | `order.value` |

Two of those choices are the whole lesson.

`order.value` is a **summary, not a counter**, because two questions are being asked of the same
event: how much revenue (`_sum`) and how large is a typical basket (`_count`, and the buckets
between). A counter of the amount answers the first and can never answer the second — the average
of a stream of numbers is not recoverable from their total.

`checkout.duration` is a **timer wrapping the whole retry loop**, not each attempt. A timer counts
as well as times, so this single meter answers all three [RED](#red-and-use) questions. Timing each
attempt separately would report a checkout that lost two optimistic locks and succeeded on the
third as three quick checkouts — none of which anybody actually experienced.

### Never graph a counter

A counter resets to zero when the process restarts, so its raw value is meaningless. Every panel
uses `rate()`, which understands that a drop to zero is a restart rather than negative traffic:

```promql
# orders per second, averaged over five minutes
rate(orders_placed_total[5m])
```

### The one that bites: an absent series is not zero

`CheckoutMetrics` registers **every** meter in its constructor, before a single order is placed —
including the four failure outcomes that may never happen. This looks like ceremony and is not.

A meter that has never been touched does not appear in the scrape at all. PromQL over a series
that does not exist returns **no rows** — not zero — so a panel shows "No data", and, far worse, an
alert written as `rate(...) > 0` can never fire, because there is nothing for the expression to be
true about. Registering up front means the series exists at `0` from the first scrape, and "no
orders in the last hour" becomes a fact the monitoring system can see rather than a silence
indistinguishable from health.

```console
$ curl -s localhost:8080/actuator/prometheus | grep checkout_duration_seconds_count
# a freshly started application, before anyone has bought anything
checkout_duration_seconds_count{application="ecomdemo",outcome="conflict"} 0
checkout_duration_seconds_count{application="ecomdemo",outcome="empty_cart"} 0
checkout_duration_seconds_count{application="ecomdemo",outcome="error"} 0
checkout_duration_seconds_count{application="ecomdemo",outcome="out_of_stock"} 0
checkout_duration_seconds_count{application="ecomdemo",outcome="placed"} 0
```

### Tags are time series, so keep them countable

`checkout.duration` carries one tag, `outcome`, whose values come from an enum with five
constants. That bound is the point. Every distinct tag value is a separate time series, stored for
ever; tagging by an order id, a username or an exception message is the textbook way to take a
monitoring system down with the traffic of the thing it is monitoring.

Spring Boot applies the same discipline to the HTTP timers it auto-configures: the `uri` tag is a
**template**, `/api/orders/{id}`, not the path that was requested. One series covers every order
ever fetched.

The reverse mistake is collapsing tags that should be separate. The five outcomes are not one
"failed" bucket, because the three ways of not creating an order call for three different
reactions: `empty_cart` rising is a front-end bug, `out_of_stock` rising is a merchandising
problem, and `conflict` rising is contention a human should look at.

### RED and USE

Two checklists for deciding what to put on a dashboard, and they apply to different things.

**RED** is for anything that serves requests — **R**ate, **E**rrors, **D**uration. The dashboard
answers all three for checkout from the one timer:

```promql
sum by (outcome) (rate(checkout_duration_seconds_count[5m]))                 # rate, and errors
histogram_quantile(0.95, sum by (le) (rate(checkout_duration_seconds_bucket[5m])))  # duration
```

**USE** is for resources — **U**tilisation, **S**aturation, **E**rrors. Heap and CPU are
utilisation; `hikaricp_connections_pending` is the saturation signal and the one to watch, because
threads queueing for a database connection means requests are waiting before a single query has
run.

### Histograms, not percentiles

The application publishes bucket counts and lets Prometheus compute the quantile:

```properties
management.metrics.distribution.percentiles-histogram.checkout.duration=true
```

Not `percentiles`, which computes p95 inside the JVM. A percentile calculated in one process
cannot be combined with another process's — averaging the p95 of three instances gives the p95 of
nothing. Buckets can simply be added up, which is why `histogram_quantile()` over three instances
is correct and an average of three p95s is not. Buckets cost one time series each, so it is an
opt-in list rather than a global switch.

### Liveness is not readiness

Two health groups, two different questions, two different remedies:

| | Asks | If DOWN, do this | Includes |
|---|---|---|---|
| `/actuator/health/liveness` | is this process broken beyond recovery? | kill and restart it | the JVM's own lifecycle flag, nothing else |
| `/actuator/health/readiness` | can it serve a request right now? | take it out of the load balancer and wait | lifecycle + the database |

Getting these the wrong way round is an outage amplifier. Wire a restart to the *readiness*
answer and a slow database makes every instance report not-ready, the orchestrator restarts all of
them at once, and now there is a thundering herd of cold JVMs on top of a database that was merely
slow.

**Redis is deliberately not in readiness.** The cache is an optimisation: if it is down every read
still works, just slower. Including it would take the whole application out of rotation over a
degradation it can absorb — a one-line configuration choice that decides whether a cache outage is
an outage.

And nothing external is in liveness, because restarting this JVM cannot fix somebody else's
database, and trying is how a dependency's bad ten minutes becomes an hour of restart loops.

The container health check uses readiness, which is what `condition: service_healthy` in
`compose.yaml` now waits for:

```dockerfile
HEALTHCHECK ... CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1
```

### What is exposed, and to whom

`management.endpoints.web.exposure.include` is an explicit allow-list — never `*`. That list, not
an authorization rule, is why `/actuator/env`, which would print the environment including
`JWT_SECRET`, is a **404 even for an administrator**.

| Endpoint | Who | Why |
|---|---|---|
| `/actuator/health`, `/actuator/info` | anonymous | their callers are machines with no credentials — the container health check, and every orchestrator probe after it |
| `/actuator/prometheus` | anonymous | Prometheus carries no token, and a 15-minute JWT would need re-issuing every 15 minutes for ever |
| `/actuator/metrics`, everything else | `ROLE_ADMIN` | written as `EndpointRequest.toAnyEndpoint()`, so an endpoint exposed later is closed by default |

The rules match by **endpoint ID**, not by URL, so they keep meaning the right thing if
`management.endpoints.web.base-path` ever moves — a hand-written `"/actuator/health"` matcher
would silently match nothing and fall through to the rule below it.

An anonymous caller gets `{"status":"UP"}` and nothing else; `show-details=when-authorized` means
the per-component breakdown needs an admin, because the component names alone map the
infrastructure for whoever asks.

Leaving the scrape endpoint anonymous is a **bounded, deliberate trade-off for a local stack**, not
a recommendation. The page carries no customer data, but it does describe the system. In a real
deployment the answer is not authentication, it is reachability: move the management endpoints to
their own port with `management.server.port` and publish that port only on the internal network,
so the question never reaches Spring Security at all.

### One alert, and why only one

```yaml
expr: |
  sum(rate(checkout_duration_seconds_count{outcome="conflict"}[5m]))
    / sum(rate(checkout_duration_seconds_count[5m])) > 0.05
for: 10m
```

Three decisions are in those four lines.

It is a **share, not a count**, so the rule means the same thing at ten orders a minute and at ten
thousand. A threshold on the raw count would page someone on Black Friday and stay silent during an
outage at 4am.

It watches **`conflict` alone**. `out_of_stock` and `empty_cart` rise because customers did
something; paging for those teaches people the alert is noise. `conflict` rises because the
application is losing optimistic locks it cannot absorb, which is a system problem with a human fix.

**`for: 10m`** is what separates an alert from a graph. Without it, one unlucky fifteen-second
window during a deployment pages somebody.

And there is exactly one rule, because an alert is a promise that a human will be woken up and will
have something to do about it. A file of thirty aspirational rules is how a team learns to ignore
all of them.

### Everything is provisioned

The Grafana datasource and dashboard are files in `docker/grafana/`, read at startup. Nothing was
clicked. `allowUiUpdates: false` means edits made in the UI are overwritten on the next reload —
the UI stays useful for exploring, it is simply not where work is kept.

`DashboardMetricsTest` closes the loop: it reads the shipped dashboard JSON and the alert rules,
pulls every metric name out of the PromQL, and fails the build if one is not a series the
application actually registers. This exists because a metric name is a public interface with no
compiler behind it — rename `orders.placed` and everything still builds, every test still passes,
and a dashboard panel quietly goes blank while an alert becomes one that can never fire again.
Monitoring fails silent by construction, which is exactly backwards from how a test suite fails.

### Try it yourself

```bash
docker compose up -d --build

# Prometheus: Status -> Targets should show `ecomdemo` UP
open http://localhost:9090/targets

# Grafana: the EcomDemo Overview dashboard is the home page (admin/admin)
open http://localhost:3000
```

Now make something happen. Log in, buy something, and watch the counter move:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"asha","password":"correct-horse-battery-staple"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')

curl -s localhost:8080/actuator/prometheus | grep '^orders_placed_total'
# orders_placed_total{application="ecomdemo"} 0.0

curl -s -X POST localhost:8080/api/cart/items -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"productId":1,"quantity":1}'
curl -s -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" > /dev/null

curl -s localhost:8080/actuator/prometheus | grep '^orders_placed_total'
# orders_placed_total{application="ecomdemo"} 1.0
```

Then check out an **empty** cart and watch a different series move — this is the `outcome` tag
earning its place:

```bash
curl -s -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" > /dev/null   # 409
curl -s localhost:8080/actuator/prometheus | grep checkout_duration_seconds_count
# ...outcome="empty_cart"} 1     <- moved
# ...outcome="placed"} 1
# ...outcome="conflict"} 0       <- the one the alert watches, untouched
```

And see the two probes disagree, which is the point of having two. Stop Redis and readiness stays
UP, because the shop still works without a cache:

```bash
docker compose stop cache
curl -s localhost:8080/actuator/health/readiness   # {"status":"UP"}
docker compose start cache
```

Stop the database and readiness goes DOWN — while liveness stays UP, because restarting this JVM
would not bring PostgreSQL back:

```bash
docker compose stop db
curl -s -w ' HTTP=%{http_code} in %{time_total}s\n' localhost:8080/actuator/health/readiness
# {"status":"DOWN"} HTTP=503 in 10.07s
curl -s localhost:8080/actuator/health/liveness    # {"status":"UP"}
docker compose start db
```

Notice the **ten seconds**. A health check is only as fast as its slowest indicator, and the
`db` one waits out the PostgreSQL driver's `connectTimeout` before it can say anything at all.
That is worth knowing before writing a probe timeout, because it means an unreachable database and
a merely slow one are the same observation for the first ten seconds.

It also interacts with the container health check, whose `--timeout=3s` is shorter than that. When
the database is gone, `wget` gives up before readiness answers, so the check fails by *timeout*
rather than by reading the 503 — and after `--retries=5` at `--interval=10s`, Docker marks the
container `unhealthy` either way:

```console
$ docker inspect -f '{{.State.Health.Status}}' ecomdemo-app
unhealthy
```

The outcome is right, and the route to it is worth being clear-eyed about: the check is reporting
"did not answer in time", not "answered DOWN". Both are correct here, because a readiness probe
that cannot answer within its budget is not ready — but a timeout cannot tell you *why*, which is
the argument for keeping a probe's own timeout above its slowest indicator once there is more than
one thing in the group.

## Centralized logging

Phase 15 made the system measurable. This phase makes it **explicable**. A metric says seventeen
requests returned 409 in the last five minutes; it cannot say which seventeen, or what each of
them was trying to do. A log line can — but only if you can find the lines that belong together,
and that is the problem this phase solves.

Three pieces, and the order matters:

| | |
|---|---|
| **Structured JSON** | Spring Boot writes the console log as Elastic Common Schema JSON, so every line is named fields rather than a sentence to regex |
| **A correlation ID** | One ID per request, in the MDC for every line the request produces, and returned in `X-Correlation-Id` |
| **Loki + Alloy** | Alloy reads the containers' stdout and pushes to Loki; Grafana queries it next to the metrics |

### The log line

Set `LOG_FORMAT=ecs` (compose does) and the same event changes shape:

```text
# without it — for a human, on a laptop
2026-09-22 16:07:30.240 INFO 1 --- [http-nio-8080-exec-2] c.e.l.RequestLogFilter : POST /api/orders -> 201 in 43ms
```

```json
{"@timestamp":"2026-09-22T10:48:36.025754428Z",
 "log":{"level":"INFO","logger":"com.ecomdemo.logging.RequestLogFilter"},
 "process":{"pid":1,"thread":{"name":"http-nio-8080-exec-5"}},
 "service":{"name":"ecomdemo","version":"0.0.1-SNAPSHOT","environment":"dev",
            "node":{"name":"971fb414b70a"}},
 "message":"POST /api/orders -> 201 in 43ms",
 "correlation_id":"smoketest-abc123",
 "ecs":{"version":"8.11"}}
```

Nothing was added to the application to produce this beyond two properties. Spring Boot has
emitted structured logs natively since 3.4 — the `logback-spring.xml` and logstash encoder that
older tutorials start with are no longer needed. **ECS** is a published field vocabulary, so
`log.level` and `error.stack_trace` mean the same thing here as in any other system that speaks
it; the alternative is inventing a private schema that every query then has to learn.

`LOG_FORMAT` is an environment variable rather than a fixed property because the format is a
deployment decision: in the container the log is parsed by a machine, on a laptop it is read by a
person. Unset means the readable pattern layout, which is what `./mvnw spring-boot:run` gets.

### The correlation ID

`CorrelationIdFilter` reads `X-Correlation-Id`, or generates one, and puts it in the **MDC** — a
`ThreadLocal` map that SLF4J attaches to every line that thread writes afterwards. That is what
makes the ID appear on lines written by Hibernate and Spring Security, classes that have never
heard of this application. The alternative is passing an ID through every method signature in the
codebase, which is why almost nobody correlates logs by hand.

Three details in that filter are each worth more than they look:

- **It runs before Spring Security** (`HIGHEST_PRECEDENCE`, ahead of the chain at `-100`). A 401
  is answered inside the security chain and never reaches a controller — so a filter ordered after
  it would leave exactly the requests you are most likely to be investigating with no ID at all.
- **It clears the MDC in a `finally`.** The thread goes straight back to Tomcat's pool. An ID left
  behind files the *next* request's lines under the previous request's story — wrong rather than
  missing, and invisible in the output.
- **It validates an inbound ID** against `[A-Za-z0-9_-]{8,64}` and replaces it when it fails. A
  header is attacker-controlled text on its way into the record of what happened; a newline in it
  forges an entire extra log entry. A bad ID is replaced rather than rejected — refusing the
  request would turn a diagnostics feature into an availability problem.

```bash
# Every response carries one, including the ones that failed
curl -i -s http://localhost:8080/api/products | grep -i correlation
# X-Correlation-Id: afc2c26644484e95807abd442d7524b1

curl -i -s http://localhost:8080/api/cart | grep -iE '^HTTP|correlation'
# HTTP/1.1 401
# X-Correlation-Id: a245f68b48f54e28b14fbace43d33971

# Send your own and it is reused — this is what makes an ID work across a boundary
curl -i -s -H 'X-Correlation-Id: my-investigation-001' \
     http://localhost:8080/api/products | grep -i correlation
# X-Correlation-Id: my-investigation-001
```

### What is deliberately NOT logged

`RequestLogFilter` writes one line per request — method, path, status, duration — and quotes
nothing from it. No headers, no body, no query string. The `Authorization` header carries a token
that is as good as a password until it expires; a login body carries the password itself; a query
string is where an API key ends up when a client takes a shortcut. Logs are replicated, retained
after the data they describe is deleted, and readable by people who have no business reading
customer data.

This is asserted rather than asserted-about: unit tests send a token, a password and an `api_key`
and require them absent, and the smoke test searches the **live Loki** for the very credentials it
has been using during the run.

```bash
# Nothing, and it should be nothing
curl -sG http://localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={service_name="app"} |= `smoke-test-password`' \
  --data-urlencode 'since=15m' | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["result"])'
# []
```

### Loki, and why it is not Elasticsearch

Elasticsearch indexes the content of every line: fast full-text search, and an index that costs
more to store and run than the logs. **Loki indexes only labels** and stores the line as a
compressed chunk, then brute-forces the content at query time over just the chunks the labels
selected. Cheaper by an order of magnitude; a query that names no labels is a slow scan. Same
shape as Prometheus, by the same authors, on purpose — which is why one Grafana queries both.

The consequence you have to design around is **cardinality**:

| | Where it goes | Why |
|---|---|---|
| `service_name`, `container` | Label | A handful of values; every query starts by narrowing to them |
| `level` | Label | Five values, and "show me the errors" is what everyone types first |
| `correlation_id` | **Structured metadata** | One value per request. As a label it would be one Loki *stream* per request — the documented way to bring Loki down |

Structured metadata is attached to the line, stored with the chunk and searchable with
`| correlation_id = "..."`, but it is not part of the index. High-cardinality identifiers belong
there, and this is the single most important thing to get right when running Loki.

### The pipeline

```text
app container ──stdout──> Docker ──> Alloy ──parse JSON──> Loki <──query── Grafana
   (ECS JSON)                        (ships)               (stores)
```

The application knows nothing about Loki, and that is the design rather than an omission. An
application that ships its own logs has to buffer, retry, and decide what to do when the log store
is down — if it blocks, a logging outage becomes an application outage; if it drops, the lines lost
are the ones written while something was already wrong. Writing to stdout is a file write that
cannot fail interestingly.

That claim is tested. With Loki stopped, the application answered `200` with no added latency, and
the line written during the outage arrived in Loki after it came back:

```bash
docker stop ecomdemo-loki
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Correlation-Id: outage-1790074614' \
     http://localhost:8080/api/products
# 200

docker start ecomdemo-loki
# ...and the line is there once Loki is ready: Alloy buffered it and retried.
```

### Finding one request's logs

The phase's acceptance criterion, three ways:

```bash
# 1. The API
curl -sG http://localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={service_name="app"} | correlation_id = `my-investigation-001`' \
  --data-urlencode 'since=15m'

# 2. Grafana -> the "EcomDemo Logs" dashboard (http://localhost:3000, admin/admin)
#    Paste the ID into the "Correlation ID" textbox at the top.

# 3. Grafana -> Explore -> Loki. Expand any log line and click
#    "All logs for this request" — a DERIVED FIELD in the datasource turns the ID
#    inside the JSON into a link to every other line that shares it.
```

The logs dashboard also carries lines per second by level, an error count, and a second panel for
PostgreSQL and Redis — because the explanation for an application error is sometimes a line the
database wrote a second earlier, and having to go and find that in `docker logs` is exactly the
friction centralized logging removes.

### Where to look when a line does not arrive

| Symptom | Look at |
|---|---|
| No `X-Correlation-Id` on a response | The application. `CorrelationIdFilter` is a `@Component`; nothing else is involved |
| Lines in Loki, but as text with no `level` label | `LOG_FORMAT` is not `ecs`, so Alloy's JSON stage has nothing to parse |
| No lines at all | Alloy's UI at <http://localhost:12345> — which containers it discovered and what it is failing to send |
| `Datasource not found` in Grafana | The datasource UID. Provisioning is read at **startup**: `docker compose restart grafana` after adding one |
| Loki answers 503 | `/ready` is 503 until the ingester has joined its ring. It resolves itself; the smoke test polls for 30s |

## Code quality

Two tools, answering two different questions. **JaCoCo** measures which lines the tests actually
ran. **SonarQube** reads the code and reports what is wrong with it. Neither is a substitute for
the other, and neither is a substitute for tests that assert something.

### Coverage across both suites

```bash
./mvnw clean verify
open target/site/jacoco-merged/index.html
```

```
INSTRUCTION  96.5%   LINE  97.3%   BRANCH  81.0%   CLASS  100%
```

The interesting part is *how* that is measured. Surefire and Failsafe fork **separate JVMs**, so
JaCoCo runs two agents and merges the results:

```
prepare-agent             -> target/jacoco-unit.exec     (Surefire)
prepare-agent-integration -> target/jacoco-it.exec       (Failsafe)
merge  + report           -> target/site/jacoco-merged/
```

A single `prepare-agent` — the configuration in most tutorials — instruments the unit run only.
Here that would have silently excluded the 30 integration tests, which are the only ones
exercising the controllers over real HTTP. The report would have been wrong in the direction that
flatters.

There was a second trap already in the pom. Surefire and Failsafe both pin an `<argLine>` for the
Mockito agent, and a literal `<argLine>` **overrides** the one JaCoCo injects — coverage would
have read 0% with nothing to explain it. Both now read it from a property, with `@{...}` late
evaluation:

```xml
<argLine>@{jacocoUnitArgLine} -javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>
```

`@{...}` is resolved when the JVM is forked; `${...}` would expand at parse time, when the
property is still empty.

### Running an analysis

```bash
docker compose -f compose.sonar.yaml up -d      # ~2 GB, first boot takes a minute
scripts/sonar-setup.sh                          # creates the project and the quality gate
open http://localhost:9000                      # admin / admin, then change it

./mvnw clean verify sonar:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=<a token from My Account -> Security> \
  -Dsonar.qualitygate.wait=true

docker compose -f compose.sonar.yaml down       # stop, keeping the analysis history
```

SonarQube lives in **its own compose file** because it needs ~2 GB and an embedded Elasticsearch,
while the application stack needs neither — `docker compose up` stays the lean two-container dev
stack.

`verify` before `sonar:sonar` is not optional: the scanner reads the compiled classes and the
merged JaCoCo report, and analysing without them reports no coverage at all.
`-Dsonar.qualitygate.wait=true` makes the **build** fail on a red gate, rather than printing a
link to a dashboard nobody opens.

### Bugs, vulnerabilities, smells and hotspots

Four categories, and the distinction is the point:

| | What it means | Example found here |
|---|---|---|
| **Bug** | Code that is wrong — it will misbehave | `new SecureRandom()` on every call |
| **Vulnerability** | Code that is exploitable | — |
| **Security hotspot** | A *question*, not a defect: security-sensitive code a human must judge | CSRF disabled |
| **Code smell** | Correct, but harder to maintain than it needs to be | a method named `record` |

A hotspot is the one people get wrong. It is not "a vulnerability we haven't fixed" — it is code
where the right answer depends on context the analyser cannot see. The gate demands that hotspots
are **reviewed**, not that there are none.

### Technical debt

Sonar prices every smell in minutes and adds them up — this project started at **92 minutes** and
is now at **0**. Treat that as a relative signal, not a schedule: the estimates are generic, and
"we have 40 hours of debt" is a sentence to be suspicious of. What the number is good for is
noticing it grow.

Complexity gets the same treatment. **Cyclomatic complexity** counts branches — how many paths
through a method. **Cognitive complexity** weights them by how hard they are for a *person* to
follow, so nesting costs more than a flat sequence of `if`s. This project sits at 216 and 28.

### The quality gate, and why it only looks at new code

```
new_coverage                   >= 80%
new_violations                 = 0
new_duplicated_lines_density   <= 3%
new_security_hotspots_reviewed = 100%
new_reliability_rating         = A
new_security_rating            = A
```

Every condition is on **new code**. A rule about the whole project either passes on day one and
never teaches anything, or fails on day one and gets switched off within a week. "Leave it cleaner
than you found it" is a rule a team can actually keep, and it converges on the same place without
ever blocking unrelated work.

Coverage on new code stays at Sonar's 80 rather than the 70 the phase brief suggested: the project
is at 97.4%, so lowering it would be loosening a gate it already beats.

**The gate is in Git**, not just in the server. `scripts/sonar-setup.sh` creates it through the
Web API and is idempotent — because a gate configured in SonarQube's database is deleted by
`down -v`, and a colleague starting the stack would silently get Sonar's defaults instead. That
script was tested by destroying the server and rebuilding from nothing.

### What coverage does not tell you

96.1% is a good number and it is not a claim that the code is correct. Coverage measures which
lines *ran*, not whether anything *checked the result* — a suite with no assertions at all can
reach 100%.

The 81% **branch** figure is the more honest one, because it counts decisions rather than lines.
And what actually makes this suite worth something is not the percentage: it is the Phase 6
oversell race, the Phase 8 ownership tests and the Phase 9 tampered-token test — none of which
coverage can see. The number is a floor, and the gate treats it as one.

### One finding that was reviewed rather than fixed

```java
@SuppressWarnings("java:S4502")   // "Make sure disabling CSRF protection is safe here"
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) {
```

The answer is in the section on CSRF below: this API is stateless, sets no cookie, and reads a
Bearer token from a header the client must attach deliberately, so there is no ambient authority
to forge.

It was first marked "Accepted" in SonarQube — and then the server was wiped to test the setup
script, and the finding came straight back, because that decision lived only in a database. So it
moved into the code, where it is version-controlled, visible in review, and where deleting the
annotation is what makes the rule speak up again the day this application adopts cookies.

## How CI works

Every pull request is built and tested by [`.github/workflows/ci.yml`](.github/workflows/ci.yml)
before it can be merged, and every merge to `main` publishes an image. **A PR may be merged only
when CI is green.**

### CI, and the CD this project does not have

**Continuous Integration** is the "build and test every change, automatically" half: catch a
break within minutes of the commit that caused it, while the author still remembers what they
were doing. **Continuous Delivery/Deployment** is the other half — actually shipping it.

This project has CI. The publish job pushes an image to a registry and stops there: nobody pulls
it, nothing runs it. Calling that "CI/CD" would be generous, and the distinction is worth keeping
because "we have CI/CD" usually means exactly this.

### Workflows, jobs, steps, runners

```
WORKFLOW   ci.yml — triggered by events (a pull request, a push to main)
└── JOB    build, publish — each gets a fresh RUNNER, a clean VM
    └── STEP   one command or action; steps share the runner's filesystem
```

Jobs are isolated and run in parallel unless `needs:` says otherwise. That matters here:

```yaml
publish:
  needs: build
  if: github.event_name == 'push' && github.ref == 'refs/heads/main'
```

`needs: build` is what makes the gate real — no image is published from a commit whose tests
failed. Without it, the two jobs would race and a red build would still publish.

### The test job runs exactly what you run

```yaml
- run: ./mvnw -B clean verify
```

Not a CI-only profile, not `-DskipITs`. The moment CI runs something different, "green on my
machine, red in CI" becomes a category of bug that costs hours and teaches nothing. Testcontainers
needs a Docker daemon and GitHub's `ubuntu-latest` runners have one, so the command needs no
adaptation — the run log shows `postgres:18-alpine` starting in 1.4 s, exactly as it does locally.

Reports are uploaded `if: always()`:

```yaml
- name: Upload the test reports
  if: always()
  uses: actions/upload-artifact@v7
```

A step is skipped by default once an earlier one has failed — so without this the reports would
upload only when everything passed, which is precisely when nobody needs them.

### Secrets, and the one you do not have to create

`GITHUB_TOKEN` is minted for each run and expires with it. Pushing to GHCR needs nothing else —
no personal access token to store, rotate, or leak. What it does need is permission, and that is
granted as narrowly as possible:

```yaml
permissions:
  contents: read        # workflow-wide default

publish:
  permissions:
    packages: write     # only this job
```

A job that only compiles code has no business holding a credential that can write to the
registry.

### Caching

A runner is a clean machine every time, so without a cache every run re-downloads ~200 MB from
Maven Central. `actions/setup-java`'s `cache: maven` keys on the pom files — reused while the
dependencies are unchanged, rebuilt when they are not. The same idea as the Dockerfile's layer
ordering, applied to `~/.m2`.

Measured on this repository: **73 s** cold, **57 s** with the cache restored.

The image build caches too, through BuildKit into GitHub's cache (`cache-from: type=gha`), with
`mode=max` so the intermediate build stage is cached as well — which a multi-stage Dockerfile
needs.

### Image tagging

```
ghcr.io/mr-sujay-patil/ecomdemo:sha-a1b2c3d     immutable
ghcr.io/mr-sujay-patil/ecomdemo:latest          mutable
```

The SHA tag names exactly one commit for ever, so a deployment can be traced back to the code that
produced it and a rollback is a matter of naming an older one. `latest` is a convenience for "the
newest" — and precisely because it moves, it must never be what a deployment pins. `latest`
deployed twice can be two different images.

```bash
docker pull ghcr.io/mr-sujay-patil/ecomdemo:latest
```

### Dependabot

[`.github/dependabot.yml`](.github/dependabot.yml) opens a PR when a Maven dependency or an action
has a newer version, weekly. Both rot silently: a dependency with a published CVE keeps building
happily, and a pinned action keeps running the code it was pinned to.

Every update arrives as a PR and goes through the same CI as any other change, so a bump that
breaks the build is a red PR rather than a surprise later — which is why this phase comes after CI
rather than before it. The Spring modules are grouped into a single PR, because they are released
together and reviewing them apart makes no sense.

### Watching it work

```bash
gh run list --limit 5                    # recent runs
gh run watch                             # follow the one in flight
gh run view <id> --log                   # the full log
gh run download <id> -n test-reports     # the Surefire/Failsafe XML, red runs included
```

## How the container works

### Images vs containers, and why layers matter

An **image** is a stack of read-only layers plus a manifest saying how to start a process in it.
A **container** is one running instance of an image, with a thin writable layer on top. Many
containers share one image; nothing a container writes goes back into it — which is exactly why
the database needs a volume, and why `docker compose down` would otherwise lose it.

Each instruction in a `Dockerfile` produces a layer, and Docker reuses a cached layer while the
instruction and its inputs are unchanged. The first change invalidates everything after it. That
single rule drives the whole file:

```dockerfile
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline    # ~200 MB, cached until the pom changes
COPY src/ src/                            # changes constantly
RUN ./mvnw -B -q package -DskipTests
```

Copying the source first would re-download every dependency on every build. As written, a
rebuild after a one-line code change takes **5 seconds**.

### Multi-stage: what gets shipped

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build     # 556 MB of JDK + Maven + the source tree
...
FROM eclipse-temurin:21-jre-alpine AS runtime   # 287 MB, a JRE and nothing else
COPY --from=build ... 
```

Only the **last** stage is published. The compiler, Maven, the ~200 MB dependency cache and the
source code all stay in the builder. A single-stage build would ship all of it to production — a
bigger image, a longer pull, and more attack surface for no benefit. Verified: `javac` and `mvn`
are absent from the runtime image, and it contains no `.java` file.

### The layered jar

A Spring Boot fat jar is one 64 MB file, so a one-character change rewrites all 64 MB and pushes a
whole new layer. `jarmode=tools extract --layers` splits it the way the application actually
changes:

| Layer | Size | Changes when |
|---|---:|---|
| `dependencies` | ~64 MB | the `pom.xml` does |
| `spring-boot-loader` | ~300 KB | the Boot version does |
| `snapshot-dependencies` | — | (empty here) |
| `application` | ~200 KB | **you edit code** |

Copied in that order, a rebuild pushes the 200 KB layer and reuses the rest.

### Running as non-root

A container's root **is** the host's root — the isolation is namespaces, not a separate user
table. So an escape, or a bind-mounted host directory, hands over root privileges for nothing:
this application never installs a package, writes to `/etc`, or binds a port below 1024.

```dockerfile
RUN addgroup --system --gid 1001 ecomdemo \
    && adduser --system --uid 1001 --ingroup ecomdemo --no-create-home ecomdemo
USER ecomdemo:ecomdemo
```

The uid is **pinned**, not left to the distribution, because a bind-mounted file is owned by a
*number*. A uid that drifts between rebuilds becomes "permission denied" on a mount that worked
yesterday.

### Sizing the JVM for a container

Modern JVMs read the container's memory limit rather than the host's — but default the heap to
**25%** of it. Asked both ways inside the running container:

```
default (25%):    192 MB
with JAVA_OPTS:   576 MB
container limit:  768 MB
```

Without `-XX:MaxRAMPercentage=75.0` this container would run a 192 MB heap and leave 576 MB
unused, spending its life in GC. A percentage follows the limit wherever the image runs; an
`-Xmx576m` would be wrong the moment `APP_MEMORY_LIMIT` changes. 75% and not more, because
metaspace, thread stacks, the code cache and direct buffers are not heap.

Note the limit has to exist for the percentage to mean anything — hence `deploy.resources.limits.memory`
in `compose.yaml`. Without it, "75% of the container" quietly means 75% of your laptop.

`-XX:+ExitOnOutOfMemoryError` is there because a JVM that has exhausted its heap will not recover,
and a container that keeps passing health checks while failing every request is worse than one
that dies and gets restarted.

### Container networking

```yaml
environment:
  POSTGRES_HOST: db
  POSTGRES_PORT: 5432
```

`db` is a **hostname**, resolved by Compose's own DNS on the network it creates for the project.
Not `localhost` — inside a container, localhost is *that container*. And not the published host
port either: this connection never leaves the Docker network, so it keeps working if you move the
published port to avoid a clash, or publish none at all. The published `5432` exists only so
`psql`, DBeaver and the smoke test's schema checks can reach the database from the host.

### Waiting for the database, properly

```yaml
depends_on:
  db:
    condition: service_healthy
```

The short form — `depends_on: [db]` — waits only for the container to be **started**, which for
PostgreSQL means the process exists. It accepts connections several seconds later. An application
that connects in between dies at startup with a Flyway error, intermittently, which is a
miserable thing to debug. `pg_isready` in the database's health check is what turns "started"
into "ready".

### Volumes

```yaml
volumes:
  - postgres-data:/var/lib/postgresql
```

A container's writable layer dies with it; a volume does not. `docker compose down` removes the
containers and keeps the data; `docker compose down -v` is the deliberate way to throw it away.

A **named** volume rather than a bind mount to a host path: Docker owns the storage, so file
ownership and permissions stay out of it — the single most common way a database bind mount
breaks on macOS and Windows.

One trap worth knowing: the mount point is `/var/lib/postgresql`, **not** the
`/var/lib/postgresql/data` every older tutorial shows. `postgres:18` moved its data into a
major-version subdirectory so `pg_upgrade --link` can work inside one mount, and mounting the old
path makes the container refuse to start.

### Why a Dockerfile and not Buildpacks

`./mvnw spring-boot:build-image` produces a working image with no Dockerfile at all. Both were
built and measured here:

| | Dockerfile | Buildpacks |
|---|---:|---:|
| Image size | **410 MB** | 766 MB |
| Cold build | 34 s | 160 s |
| Rebuild after a one-line change | **5 s** | 53 s |
| Non-root | yes (uid 1001, ours) | yes (uid 1002, automatic) |

Buildpacks get the hard parts right without being asked — layering, the non-root user, the JVM
container flags — and rebase for CVE fixes without a rebuild, which makes them the better default
for a team that would rather not think about images. This project exists to think about them, and
a Dockerfile is what makes the layering, the user and the flags visible and reviewable. It is also
356 MB smaller and ten times faster to rebuild.

## How the security works

### The filter chain

Adding `spring-boot-starter-security` puts a single servlet `Filter` in front of the whole
application before a line of our code runs, and that filter delegates to an ordered chain of
small ones. Each does one job and hands the request on: work out who is calling
(`BearerTokenAuthenticationFilter`, reading the `Authorization` header and verifying the JWT),
put the result in the `SecurityContextHolder`, and finally decide whether this caller may have
this URL (`AuthorizationFilter`).

Phase 9 replaced only the first of those steps. `BasicAuthenticationFilter` used to read a
username and password, look the account up and run BCrypt — on **every** request. The token
filter replaces all three with a signature check over bytes the request already carries.

The consequence worth remembering: **a request the chain rejects never reaches Spring MVC.**
There is no controller, no handler method and no `@RestControllerAdvice` to run — which is why
401 and 403 cannot be produced by `GlobalExceptionHandler` like every other error, and are
written instead by an `AuthenticationEntryPoint` and an `AccessDeniedHandler`
(`security/ApiError*`). They exist so that those two statuses come back in the same shape as
everything else.

The rules are in `security/SecurityConfig`, they are **ordered, and the first match wins**:

```java
.requestMatchers(HttpMethod.POST, "/api/customers/register").permitAll()
.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
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

- **Authentication — "who are you?"** At `/api/auth/login`, `AppUserDetailsService` looks the
  username up and the `PasswordEncoder` decides whether the password matches the stored hash.
  On every other request, the `JwtDecoder` checks a signature instead. Two responsibilities, two
  beans — which is exactly why this phase could replace the *how* without touching the hashing,
  the user store or a single authorization rule.
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

### What a JWT actually is

Three Base64url segments joined by dots:

```
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJhc2hhIiwidWlkIjo0fQ . 3nK9r...
└── header ────────┘   └── payload (claims) ────────┘   └ signature
```

The **header** names the algorithm. The **payload** is the claims — here `iss`, `sub`, `uid`,
`roles`, `iat`, `exp`. The **signature** is an HMAC over the first two segments.

Two things follow, and they are the whole model:

1. **The payload is encoded, not encrypted.** Anyone holding the token can read it, exactly as
   anyone could read a Base64 Basic header. So nothing secret goes in a claim — the token carries
   an id, a username and a role, and that is all.
2. **The signature covers the header as well as the payload.** Change one character of either and
   verification fails. That is what makes it safe to authorize from claims without looking
   anything up, and it is also why the header being signed matters: it stops an attacker
   rewriting `alg` to `none` and presenting an unsigned token, the classic JWT vulnerability. The
   decoder is additionally pinned to HS256, so even a validly signed token using some other
   algorithm is refused.

### Stateless vs session-based

The alternative to a token is a session: the server keeps the truth in a store and hands the
client an opaque id.

|  | Session | Token |
|---|---|---|
| Where the truth lives | On the server | In the token, signed |
| Scaling out | Every instance needs the shared store | Any instance can verify alone |
| Logging someone out | Delete the row — immediate | Not possible before `exp` |
| A role change | Takes effect on the next request | Takes effect when the token expires |

Neither is better; they trade the same property in opposite directions. This application chose
the token, so it pays the revocation cost — and the mitigation is the only one available: keep
the expiry short. Fifteen minutes here.

### HMAC vs RSA

HS256 signs and verifies with the **same** secret. That is fine while one application does both
jobs, as this one does. The moment a second service needs to accept these tokens, it becomes the
wrong choice: handing that service the key to verify with also hands it the power to **issue**.

RS256 splits the two — a private key that signs, a public key that anyone may hold and verify
with. That is why every real identity provider publishes a JWKS endpoint of public keys and no
secrets at all, and it is the change to make when this monolith becomes several services.

### Expiry, refresh and revocation

A token cannot be withdrawn. Nothing consults a database once one is issued, so:

- a role taken away stays effective until the token expires;
- a deleted account keeps working until the token expires;
- there is no "log out everywhere".

A short expiry is the only lever, and a **refresh token** is what normally makes a short expiry
comfortable: a second, longer-lived, single-purpose credential that *is* stored server-side, so
it can be revoked, and whose only power is to mint a new access token. This phase deliberately
does not build one — see "Known gaps". Production systems that need instant revocation add a
deny-list of token ids checked on each request, which trades some of the statelessness back.

### What OAuth2 and OIDC would add

This application issues and accepts its own tokens, which is not really OAuth2 — it is just JWTs.
The Spring module is called `oauth2-resource-server` because "resource server" is OAuth2's name
for an API that *accepts* tokens, and that half fits.

**OAuth2** is a delegation protocol: it exists so a user can let one application act on their
behalf at another *without handing over their password*, with an authorization server issuing
scoped tokens in the middle. **OIDC** is a thin layer on top that adds identity — an `id_token`
saying who the user is, and a standard `/userinfo` endpoint — which is what "Sign in with Google"
actually is. Adopting either would mean deleting `AuthController` and `TokenService` and pointing
the decoder at an external issuer's JWKS URL. The rest of this application would not change,
which is the payoff of having kept authentication and authorization apart.

### Why CSRF is switched off

Cross-Site Request Forgery is an attack on **ambient authority**: a browser attaches its session
cookie to any request aimed at that origin, including one triggered by a form on
`evil.example.com`, so the server sees a perfectly authenticated request the user never meant to
send. The defence is a token the attacker's page cannot read and therefore cannot include.

None of that applies here. This API keeps no session and sets no cookie; the token arrives in an
`Authorization` header that the client must attach deliberately on every call, and a browser will
never do that by itself. A cross-site form submission simply arrives with no token and is answered
401. There is no ambient authority to forge, so a CSRF token would protect nothing and would break
every non-browser client.

This is also the argument for *not* storing a JWT in a cookie, however convenient that is: the
moment the browser sends it automatically, CSRF is back and so is the need for the protection.

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
./mvnw test      # 241 tests, ~15 s, in-memory H2, no Docker
./mvnw verify    # those 241 PLUS 62 integration tests against a real PostgreSQL and Redis
```

`./mvnw test` is the inner loop: it needs nothing installed and it is what you run constantly.
`./mvnw verify` additionally starts PostgreSQL in a Docker container and runs the whole
application against it — it needs **Docker running**, and it is what has to be green before a
commit is pushed.

The fast suite sits at five levels, each loading only what it needs:

| Level | Annotation | What it loads | Classes |
|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | Nothing — plain objects with mocked collaborators | `ProductServiceTest`, `CartServiceTest`, `OrderServiceTest`, `OrderPlacementServiceTest`, `CustomerServiceTest`, `AppUserDetailsServiceTest`, `AuthServiceTest`, `TokenServiceTest`, `CurrentUserTest` |
| Web slice | `@WebMvcTest` | The controller, JSON conversion, validation, the error handler **and the real security rules**; services are `@MockitoBean` | `ProductControllerTest`, `CartControllerTest`, `OrderControllerTest`, `CustomerControllerTest`, `AuthControllerTest` |
| Persistence slice | `@DataJpaTest` | JPA and its own throwaway H2 database; no web layer, no Flyway | `CartRepositoryTest`, `OrderRepositoryTest` |
| Full context | `@SpringBootTest` | The whole application, on a schema Flyway migrated | `PlaceOrderFlowTest`, `OpenApiDocumentationTest`, `FlywayMigrationTest`, `ConcurrentCheckoutTest`, `BatchJobRepositoryTest`, `SalesReportScheduleTest` |
| Configuration | `ApplicationContextRunner` | A handful of beans and the properties, resolved as at startup | `DatasourceConfigurationTest`, `JwtConfigTest` |
| Plain file | none | Two files off disk plus a `SimpleMeterRegistry` — no Spring at all | `DashboardMetricsTest` |

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

`DashboardMetricsTest` is the other odd one, and it is at the *bottom* of the pyramid on purpose.
It loads no Spring context: it reads `docker/grafana/dashboards/ecomdemo.json` and
`docker/prometheus/alerts.yml` as text, pulls the metric names out of the PromQL, and checks each
against a registry built from the real `CheckoutMetrics` constructor. It belongs in the fast suite
because the failure it catches — a renamed meter silently emptying a panel and permanently
silencing an alert — is one nobody would otherwise notice at all, and a guard that only runs in the
slow suite is found out too late to be useful.

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
| `AuthApiIT` | 8 | Login returning a working token, the readable payload, a tampered token, a correctly-signed **expired** token, malformed rubbish, no token at all, login failures that are identical for a wrong password and an unknown username, and the roles claim deciding what the token may do |
| `CacheApiIT` | 11 | The cache proven by changing the database behind its back with direct SQL: a repeated read never reaches PostgreSQL, an update refreshes, a delete evicts, a BigDecimal survives the JSON round trip, checkout ignores the cache, and a committed sale evicts both catalogue caches while a rolled-back one evicts neither |
| `ProductImportJobIT` | 6 | A 10,000-row import over real HTTP with the invalid rows skipped and listed, the upsert making a re-import idempotent, and the restart of a run that died on the skip limit |
| `SalesReportJobIT` | 3 | The report's contents against orders placed through the real checkout, a quiet day, and a second run for the same day being refused |

Since Phase 9 these tests do the whole round trip: they call `POST /api/auth/login` over HTTP, get
a genuinely signed token back, and send it as `Authorization: Bearer` — so the password is checked
against the BCrypt hash migration V5 put into the container's database, and every later request
has its signature, expiry and issuer verified by the real decoder. That is the only layer where
the rules, an actual login *and* a real signature are exercised together. `@WithMockUser` in the
slices skips the authentication, and a hand-built `Jwt` would skip the signature.

Two of those classes are worth singling out. `BatchJobRepositoryTest` asserts on **rows in the
`BATCH_*` tables** rather than on behaviour, because Spring Batch 6's in-memory default repository
makes the behaviour identical while persisting nothing — every other batch test passed against it.
`SalesReportScheduleTest` sets the cron to every second and waits for the report file to appear,
which is the only way to show that `@Scheduled` is actually wired rather than merely written.

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
define it. Every `*IT` extends one base class, `IntegrationTest`, so they all ask for the same
context: **one** PostgreSQL and **one** Redis for the whole run, counted from the log. The first
class pays about twelve seconds for the context and the containers; the rest take a fraction of a
second each. (Add a `@MockitoBean` or a stray `@TestPropertySource` to one subclass and it quietly
gets a context — and both containers — of its own.)

The suites are split by **file name**. Maven's Surefire plugin runs `*Test.java` at the `test`
phase; its sibling Failsafe runs `*IT.java` after packaging. Failsafe deliberately does not fail
the build when a test fails — it records the result, lets the build reach
`post-integration-test` so containers are always torn down, and a separate `verify` goal then
fails the build. Renaming a class from `FooTest` to `FooIT` is the whole mechanism for moving it
between suites.

What the integration tests buy, concretely: the migrations V1–V8 are applied to an empty
**PostgreSQL 18** on every build, `NUMERIC(10,2)` rounds the way the real column rounds, the
oversell race is settled by PostgreSQL's own row locking rather than H2's, and a ten-thousand-row
import runs against a database that really commits a hundred transactions. That third one used to
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

- **Nothing delivers the alert anywhere.** Prometheus evaluates `CheckoutConflictRateHigh` and
  would mark it firing, and that is where it stops: there is no Alertmanager, so no email, no
  pager, no Slack. The rule was proven to fire in shape — the identical expression with a label
  that *was* over the threshold returns a row — but an alert nobody receives is a graph with
  extra steps. Alertmanager is not in this phase's scope.
- **`/actuator/prometheus` is anonymous.** Deliberate, bounded, and not a recommendation: the
  scraper carries no token and a 15-minute JWT would need re-issuing for ever. The page holds no
  customer data but does describe the system — every URI template, the pool sizes, the heap. The
  real fix is reachability, not authentication: `management.server.port` on a port published only
  to the internal network. That is a deployment concern and was left to a later phase.
- **The dashboard has only ever had one instance to draw.** The `application` common tag and the
  choice of histograms over client-side percentiles both exist so that the panels keep meaning
  something when there are two. There is one, so neither has actually been exercised.
- **No cardinality budget is asserted anywhere.** Tag values are bounded by an enum and URI
  templates, and both facts are tested — but nothing counts total series and fails when the number
  grows. The failure mode is gradual and nobody notices it until the Prometheus host does.
- **Cache hit rate is published but not graphed.** Phase 13 listed hit-rate metrics as a follow-up
  for this phase. Spring Boot's `cache_gets_total{result=...}` is in the scrape, and no panel uses
  it; the dashboard covers checkout, HTTP and the JVM instead.
- **The fast suite is still only ever run on H2.** `./mvnw verify` runs the migrations and the
  checkout race against a real PostgreSQL container, so the gap is covered — but only by the 62
  integration tests. The other 241 still run on H2, so a PostgreSQL-specific problem in a code
  path no `*IT` exercises would still reach production.
- **Restart across a process restart is inferred, not tested.** The JobRepository is on disk and
  staged uploads are on a named volume, so an import that failed before a container was replaced
  should be restartable afterwards. Nothing in the suite kills a container and checks.
- **`@Scheduled` fires in every instance.** Two copies of the application both start the nightly
  report; the second is refused because the day's JobInstance is already COMPLETE. That refusal
  is tested, two instances actually racing are not, and a real deployment wants a leader election
  or an external scheduler rather than a JobRepository collision.
- **A token cannot be revoked before it expires.** A role taken away, or a deleted account, stays
  effective for up to fifteen minutes, and there is no "log out everywhere". That is the price of
  statelessness rather than a bug, and a short expiry is the only mitigation in place. A
  deny-list of token ids checked per request is the usual production answer, and it trades some
  of the statelessness back.
- **There is no refresh token.** The phase file lists one as optional and it was deliberately not
  built. Without it, a short expiry means users log in again every fifteen minutes — which is
  exactly the discomfort a refresh token exists to remove: a second, longer-lived, revocable
  credential whose only power is to mint a new access token.
- **Signing is symmetric (HS256).** One key both signs and verifies, which is fine while a single
  application does both. A second service that needed to accept these tokens would have to be
  given the power to issue them, so that is the point to move to RS256 and a published public key.
- **The application is its own authorization server.** A textbook OAuth2 deployment separates the
  two. Nothing here implements OAuth2 flows, scopes or OIDC; it issues plain JWTs.
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
- **Still no TLS.** A Bearer token is as sensitive as a password and travels in a header in
  clear text, so this is safe on localhost and nowhere else. Terminating TLS is the reverse
  proxy's job, and arrives with the deployment phases.
- **The compose stack is a development stack.** One replica, the `dev` profile, the database
  beside the application, and credentials in a local `.env`. A real deployment separates them,
  runs a managed database, and takes its secrets from something that is not a file — Phases 25
  and 26.
- **The image is not scanned, signed or pinned by digest.** Base images are pinned by tag
  (`eclipse-temurin:21-jre-alpine`), which is reproducible until the tag moves. Vulnerability
  scanning arrives with **Phase 31**.
- **CI does not run the smoke test.** `./mvnw verify` covers the Java; the compose stack and the
  image are still only exercised on a developer's machine. The phase file offers this as an
  optional addition and it was deliberately left out of scope — it is the most obviously worthwhile
  next thing to add to `ci.yml`.
- **There is no CD.** The publish job pushes an image to GHCR and stops. Nothing pulls it and
  nothing runs it; deployment arrives with **Phases 25–26**.
- **Nothing scans the published image.** Dependabot watches the Maven dependencies and the
  actions, not the base image or the built artefact. **Phase 31** adds scanning.
- **The quality gate is not enforced in CI.** SonarQube runs locally, on demand; nothing checks
  it on a pull request. SonarQube Cloud with PR decoration is the usual answer and was left out
  of scope deliberately — so the gate is a tool you run, not a gate that stops you.
- **Branch coverage sits at 81% against 97% line coverage.** Several `else` paths are defensive
  and only reachable through states the API does not permit. That gap is the honest one to look
  at; the line figure flatters.
- ~~**The catalogue shows stale stock for up to 10 minutes after a sale.**~~ **Closed after
  Phase 16** by an `AFTER_COMMIT` transactional listener that evicts both catalogue caches — see
  "Invalidation" above and `docs/decisions.md` [Phase 16 follow-up]. The Phase 13 reasoning for
  deferring it was sound about the hazard and wrong about the cost: the stale figure was visible to
  shoppers, who could be refused at checkout over stock the page had just advertised.
- **Nothing measures the cache hit rate.** Hit and miss are logged at DEBUG, which answers "is it
  working?" and not "is it worth it?". Hit ratios belong in metrics — **Phase 15**.
- **Redis is a single node with no password.** Fine on a compose network that publishes it only
  for `redis-cli`; a real deployment needs at least `requirepass` and a replica.
- **No coverage report.** The suite is broad but nothing measures or enforces how much of the
  code it reaches. **Phase 12** adds JaCoCo and SonarQube.
- **The build now needs Docker.** `./mvnw verify` starts a container, so a machine without
  Docker can only run `./mvnw test`. That is the deliberate trade for testing against the real
  engine, and **Phase 11** is where CI has to be given a Docker daemon of its own.
