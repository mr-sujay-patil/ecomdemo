# Fix Report: the catalogue kept advertising stock that had just been sold

- **Date:** 2026-09-22
- **Branch:** `fix/product-cache-eviction` (cut from `main` at `dc35a85`, after `phase-16-complete`)
- **Defect:** found during Phase 16's testing protocol, accepted as known at the time, fixed here
  on the user's instruction
- **Result:** ✅ `./mvnw clean verify` 273 + 73, 0 failures, 0 skipped · `scripts/smoke-test.sh`
  **234 passed, 0 failed, 0 skipped**

## 1. What was wrong

Placing an order decremented `product.stock_quantity` in the database and evicted neither Phase 13
cache. `GET /api/products/{id}` then served the pre-sale figure for up to its 10-minute TTL, and
the listing for up to its 2-minute one.

Reproduced against the running stack before the fix:

```
product 449
before  API: 4   DB: 4
order:  201
after   API: 4   DB: 2      <- the API is wrong, for up to 600s
```

The user-visible consequence is worse than "a stale number": a shopper reads "4 in stock", adds
four to a cart, and is refused at checkout — because checkout reads live through `requireProduct`
and is correct. The database was right the whole time, which is what makes it hard to diagnose
from the outside.

**This was not an oversight.** `ProductService.save` carried a comment saying it deliberately
evicts nothing, and `docs/decisions.md` recorded the trade, listing "transaction synchronisation"
as the real answer, deferred as "a phase of its own". The Phase 13 reasoning about the *hazard* was
right and still holds; what it underestimated was the cost of living with it.

## 2. The fix

```
ProductService.save(product)                     // inside the checkout transaction
  └─ publishes ProductStockChangedEvent(id)
        └─ ProductCacheEvictor  @TransactionalEventListener(AFTER_COMMIT)
              ├─ evict product::<id>
              └─ evict productList::all
```

The eviction is the same one `@CacheEvict` would perform. **Only the timing changed**, and the
timing was the entire objection:

| Hazard Phase 13 named | Why AFTER_COMMIT answers it |
|---|---|
| A failed checkout throws away good cache entries | The listener runs only if the transaction committed |
| Evicting before commit lets a concurrent read repopulate from the pre-commit row, wrong until the TTL | It runs after the new row is visible, so whatever repopulates next reads the truth |

The event carries an id, not the entity: by the time the listener runs the entity is detached and
its persistence context is gone. Publishing from `ProductService` rather than
`OrderPlacementService` keeps ordering free of any knowledge that a cache exists — and it is the
shape Phase 17 will want, since the same fact is what would be published to Kafka.

## 3. Tests

| Test | Suite | What it pins down |
|---|---|---|
| `ProductServiceTest.save_whenCalled_announcesThatTheStockChanged` | unit | the event is published, and it is *all* `save` does about the cache |
| `ProductCacheEvictorTest` (3) | unit | both caches are evicted; a missing cache is tolerated; a Redis failure cannot fail a committed order |
| `CacheApiIT.aCommittedCheckoutEvictsTheCatalogue` | integration | end to end: buy 2 of 5, and both the product page and the listing read 3 immediately |
| `CacheApiIT.aCommittedTransactionEvicts` | integration | a committed write drops the entry |
| `CacheApiIT.aRolledBackTransactionEvictsNothing` | integration | a rolled-back write leaves the cache exactly as it found it |
| smoke: 6 new checks | end to end | the regression check, with the cache **warmed first** |

**Why the smoke check warms the cache first.** The happy-path section has always read the stock
back after an order, and it passed throughout the bug — because nothing had put that product in the
cache beforehand, so its read was a miss that went to the database. A regression test for a cache
has to guarantee the entry exists before the thing it is testing happens. That is why the original
failure looked intermittent: it depended on which product the script happened to pick.

## 4. Mutation testing

| Mutation | Caught by |
|---|---|
| `save()` no longer publishes the event | `ProductServiceTest.save_whenCalled_announcesThatTheStockChanged`, and — with the unit suite skipped — `aCommittedCheckoutEvictsTheCatalogue` **and** `aCommittedTransactionEvicts` |
| `@CacheEvict` put back on `save()` (the rejected alternative) | `aRolledBackTransactionEvictsNothing` |
| `AFTER_COMMIT` → `BEFORE_COMMIT` | **nothing — the suite passed** |

That third row is worth stating rather than hiding. Spring skips before-commit callbacks entirely
on a rollback-only transaction, so both phases behave identically in every test here. What actually
separates them is a concurrent reader repopulating the cache from a not-yet-committed row, and that
is a race with no deterministic hook to test against. The tests prove the eviction is tied to the
**commit** rather than to the write; they do not prove the phase choice, and the code comment now
says so.

## 5. A second defect found on the way

Adding the eviction check pushed `order_value_sum` past six significant digits, and a Phase 15
metrics check began failing:

```
FAIL  order_value_sum grew by the order total (2499.5)
      expected: 2499.5     actual: 2499
```

The smoke test formatted metric values with `%g`, which prints **six** significant digits — so
167656.5 printed as 167656 and the delta lost the half. This had been latent for two phases and
would have started failing on its own as soon as the shop's cumulative order value grew past six
digits; the eviction check merely got there first. Fixed to `%.12g` in both `METRIC_PY` and
`delta`, which still prints `1.0` as `1` for the counter checks.

## 6. Verification

```
./mvnw clean verify      ->  273 unit + 73 integration, 0 failures, 0 skipped
scripts/smoke-test.sh    ->  234 passed, 0 failed, 0 skipped
```

Against the running stack, the original reproduction, now correct:

```
product 3
before   API: 9   DB: 9
order:   HTTP 201
after    API: 7   DB: 7
listing  API: 7
```

No sleep anywhere in that sequence: the eviction is part of finishing the checkout, so the next
read is already right. A check that needed a sleep would be a check that accepted staleness.
