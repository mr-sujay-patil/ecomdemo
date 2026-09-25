# Phase 20d Plan: extract `customer-service` and `notification-service`

> The fourth and final PR of Phase 20. When this merges and verifies, **`phase-20-complete` is
> tagged** — and `ecomdemo-app` becomes `order-service`.

## 1. Evidence gathered before planning

**Who depends on what** (from the generated module diagram, not from memory):

    Cart   -> Customer          CurrentUser, and the User the cart belongs to
    Order  -> Customer          CurrentUser, and the User who placed the order
    Security -> Customer        it loads an account to check a password
    Auth   -> Security          login uses the AuthenticationManager
    Notification -> Messaging   EventDeduplicator, KafkaTopics, OrderPlacedEvent

**Two foreign keys point at `users`**, read from the running database:

    cart.user_id   -> users
    orders.user_id -> users

That is the same shape as 20a's `cart_item -> product`, and it has the same answer.

**`CurrentUser` loads the `User` entity from `UserRepository`.** This is the crux of the phase: cart
and order do not just want an id, they hold a `User`. Across a boundary they cannot.

## 2. The three decisions this phase turns on

### 2.1 `CurrentUser` must stop loading a User ⚠️ THE CENTRAL ONE
Today it reads the `SecurityContextHolder` for a username and then fetches the account. After the
split that is an HTTP call on **every authenticated request** — the hottest path there is — to learn
something the request already carried.

**Proposal: read it from the token.** Phase 9 already puts `uid` and `roles` in the JWT, and Phase 19
moved the claim names into `shared` precisely so both sides could agree on them. So `CurrentUser`
becomes a `common` concern that reads claims, cart and order hold a `userId`, and nobody calls
customer-service to find out who is calling. That is the entire point of a signed token.

**The cost, stated plainly:** a cart line will hold a `userId` with no foreign key behind it, exactly
as it now holds a `productId`. A deleted account leaves orphaned carts. Today the FK prevents that;
tomorrow nothing does.

### 2.2 `security` splits in two, and only half moves
| Half | Goes to | Why |
|---|---|---|
| Validate a token → an authenticated principal | **every service** (mostly already in `common/jwt`) | every service must do this and none may ask another |
| Check a password, load an account, issue a token | **customer-service** | only the service that owns accounts can |

The application keeps a filter chain — it still has a public API to protect — but loses
`AppUserDetailsService` and `AuthenticationManager`. It cannot authenticate a password any more, and
should not be able to.

### 2.3 `EventDeduplicator` moves with `notification`, `messaging` keeps the outbox
notification-service takes `notification` and `processed_event`, so the deduplicator goes with the
table it guards. `messaging` keeps the outbox and `OrderPlacedEvent` — and that event is the contract
between two services now, so notification-service declares its own copy, the way 20b's stock event
did. The outbox stays in order-service only.

## 3. Order of work — each commit leaving `./mvnw clean verify` green

1. **`CurrentUser` reads the token** (in `common`), and cart/order hold a `userId`. A migration drops
   the two foreign keys. **This lands first, while there is still one deployable to verify it in** —
   the 20a lesson: do the data change on the near side of the line.
2. `customer-service` module: `users` in `customer_db` at V1, the register/profile API, login, the
   `AuthenticationManager` and the JWT **encoder**.
3. A `CustomerGateway` in `common` for whatever still needs an account by id (registration is public,
   so the app forwards it).
4. `notification-service`: `notification` + `processed_event` in `notification_db` at V1, the Kafka
   consumer, the deduplicator.
5. The application keeps a token-validating chain, and the public `/api/auth/**` and
   `/api/customers/**` forward to customer-service.
6. Compose: two more databases and two more services; Alloy, Prometheus, the Dockerfile's COPY list.
7. Smoke test across five services; full protocol; report; docs; PR. **Then tag.**

## 4. What I expect to go wrong

- **Every integration test logs in.** `IntegrationTest.asAdmin()` POSTs to `/api/auth/login`. With
  customer-service extracted, the app's own ITs have no login — they will need a fake customer or a
  minted token, the way `CatalogIntegrationTest` already does.
- **The seeded administrator.** V5 seeds `admin`; that row moves to `customer_db`, and the smoke test
  logs in as it on the very first check.
- **`order_audit` and the batch tables** reference nothing external, so they stay — but the audit row
  records a username, which is now a claim rather than a join.
- **Five JVMs.** Measured projection is ~1.9 GB against 3.8 GB, based on 170–183 MiB per service.
  That should fit; "should" is doing real work in that sentence.

## 5. Also outstanding (not this phase's subject, but it touches them)

- The smoke test's `"the checkout did not wait for the broker (under 10s)"` check **flakes on a cold
  application** — it failed once during 20c's merge verification and passed on every warm run. The
  first checkout after startup pays lazy initialisation that has grown with each extracted service,
  so the check is now measuring warm-up as much as the thing it claims. Fix: warm the checkout path
  before the timing assertion. In scope here because this phase rewrites the smoke test anyway.
