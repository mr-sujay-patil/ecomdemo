#!/usr/bin/env bash
#
# End-to-end smoke test for EcomDemo.
#
# Runs the full business flow against a RUNNING application and prints one PASS/FAIL line per
# check. Exits non-zero if any check fails, which makes it a growing regression suite: every
# later phase adds checks here and none are ever removed.
#
#   Usage:  ./mvnw spring-boot:run          # in one terminal
#           scripts/smoke-test.sh           # in another
#
#   BASE_URL overrides the target, e.g. BASE_URL=http://localhost:9090 scripts/smoke-test.sh
#
# The script is re-runnable: it empties the cart before starting, so it does not care whether
# the application was just started or has been used already.
#
# One check spans two runs. The "Persistence across restarts" section leaves a probe product
# behind and verifies it on the next run, so restarting the application between two runs is what
# proves the data is in PostgreSQL and not in memory. The probe id is kept in .smoke-state
# (override with SMOKE_STATE_FILE); deleting that file just resets the check to a first run.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

PASSED=0
FAILED=0
SKIPPED=0

pass() {
    printf '  \033[32mPASS\033[0m  %s\n' "$1"
    PASSED=$((PASSED + 1))
}

fail() {
    printf '  \033[31mFAIL\033[0m  %s\n        expected: %s\n        actual:   %s\n' "$1" "$2" "$3"
    FAILED=$((FAILED + 1))
}

check() { # check <name> <expected> <actual>
    if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "$2" "$3"; fi
}

# A check that could not be RUN is never reported as a pass. It is counted separately and the
# summary says so, so a missing psql can never be mistaken for a green result.
skip() {
    printf '  \033[33mSKIP\033[0m  %s\n        reason:   %s\n' "$1" "$2"
    SKIPPED=$((SKIPPED + 1))
}

section() {
    printf '\n\033[1m%s\033[0m\n' "$1"
}

# request <METHOD> <PATH> [JSON] -> echoes the HTTP status, body lands in $BODY
request() {
    local method="$1" path="$2" data="${3:-}"
    if [ -n "$data" ]; then
        curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE_URL$path" \
            -H 'Content-Type: application/json' -d "$data"
    else
        curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE_URL$path"
    fi
}

# jget <python expression over `d`> -> prints the value from the last response body
jget() {
    python3 -c "import json;d=json.load(open('$BODY'));print($1)" 2>/dev/null
}

# --------------------------------------------------------------------------------------------
# 0. The application must be up
# --------------------------------------------------------------------------------------------
section "Readiness"

for _ in $(seq 1 30); do
    if curl -fsS "$BASE_URL/api/products" >/dev/null 2>&1; then break; fi
    sleep 1
done

STATUS="$(request GET /api/products)"
if [ "$STATUS" != "200" ]; then
    printf '  \033[31mFAIL\033[0m  application is not responding at %s (GET /api/products -> %s)\n' \
        "$BASE_URL" "$STATUS"
    printf '\nStart it with: ./mvnw spring-boot:run\n'
    exit 1
fi
pass "application is up at $BASE_URL"

# Empty the cart so the run starts from a known state.
request GET /api/cart >/dev/null
for product_id in $(jget "' '.join(str(i['productId']) for i in d['items'])"); do
    request DELETE "/api/cart/items/$product_id" >/dev/null
done
request GET /api/cart >/dev/null
check "cart starts empty" "0" "$(jget "len(d['items'])")"

# --------------------------------------------------------------------------------------------
# 1. Happy path: list -> add -> view -> place -> read back -> stock decreased
# --------------------------------------------------------------------------------------------
section "Happy path"

STATUS="$(request GET /api/products)"
check "GET /api/products returns 200" "200" "$STATUS"

PRODUCT_COUNT="$(jget "len(d)")"
if [ "${PRODUCT_COUNT:-0}" -lt 1 ]; then
    fail "catalogue is seeded" ">= 1 product" "$PRODUCT_COUNT"
    printf '\nCannot continue without products.\n'
    exit 1
fi
pass "catalogue is seeded ($PRODUCT_COUNT products)"

# Pick the first product with enough stock to order 2 of.
PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 2)")"
PRODUCT_NAME="$(jget "next(p['name'] for p in d if p['stockQuantity'] >= 2)")"
UNIT_PRICE="$(jget "next(str(p['price']) for p in d if p['stockQuantity'] >= 2)")"
STOCK_BEFORE="$(jget "next(p['stockQuantity'] for p in d if p['stockQuantity'] >= 2)")"
QUANTITY=2
EXPECTED_TOTAL="$(python3 -c "from decimal import Decimal;print(Decimal('$UNIT_PRICE')*$QUANTITY)")"
pass "selected '$PRODUCT_NAME' (id=$PRODUCT_ID, price=$UNIT_PRICE, stock=$STOCK_BEFORE)"

STATUS="$(request POST /api/cart/items "{\"productId\":$PRODUCT_ID,\"quantity\":$QUANTITY}")"
check "POST /api/cart/items returns 200" "200" "$STATUS"

STATUS="$(request GET /api/cart)"
check "GET /api/cart returns 200" "200" "$STATUS"
check "cart has 1 line" "1" "$(jget "len(d['items'])")"
check "cart line quantity is $QUANTITY" "$QUANTITY" "$(jget "d['items'][0]['quantity']")"
check "cart total is calculated server-side" "$EXPECTED_TOTAL" \
    "$(jget "d['totalAmount']")"

STATUS="$(request POST /api/orders)"
check "POST /api/orders returns 201" "201" "$STATUS"
ORDER_ID="$(jget "d['id']")"
check "order status is PLACED" "PLACED" "$(jget "d['status']")"
check "order total matches the cart total" "$EXPECTED_TOTAL" "$(jget "d['totalAmount']")"
check "order line snapshots the product name" "$PRODUCT_NAME" "$(jget "d['items'][0]['productName']")"

STATUS="$(request GET "/api/orders/$ORDER_ID")"
check "GET /api/orders/$ORDER_ID returns 200" "200" "$STATUS"
check "order reads back with the same total" "$EXPECTED_TOTAL" "$(jget "d['totalAmount']")"

STATUS="$(request GET /api/orders)"
check "GET /api/orders returns 200" "200" "$STATUS"
check "placed order appears in the list" "True" "$(jget "any(o['id'] == $ORDER_ID for o in d)")"

STATUS="$(request GET "/api/products/$PRODUCT_ID")"
check "GET /api/products/$PRODUCT_ID returns 200" "200" "$STATUS"
check "stock decreased by $QUANTITY" "$((STOCK_BEFORE - QUANTITY))" "$(jget "d['stockQuantity']")"

request GET /api/cart >/dev/null
check "cart is empty after checkout" "0" "$(jget "len(d['items'])")"
check "cart total is zero after checkout" "0" "$(jget "int(d['totalAmount'])")"

# --------------------------------------------------------------------------------------------
# 2. Negative cases
# --------------------------------------------------------------------------------------------
section "Negative cases"

STATUS="$(request GET /api/products/999999)"
check "unknown product returns 404" "404" "$STATUS"
check "404 body carries the status field" "404" "$(jget "d['status']")"
check "404 body carries a message" "True" "$(jget "bool(d['message'])")"

STATUS="$(request POST /api/cart/items "{\"productId\":$PRODUCT_ID,\"quantity\":0}")"
check "quantity 0 returns 400" "400" "$STATUS"
check "400 body carries the status field" "400" "$(jget "d['status']")"

STATUS="$(request POST /api/cart/items '{"quantity":1}')"
check "missing productId returns 400" "400" "$STATUS"

STATUS="$(request POST /api/cart/items "{\"productId\":999999,\"quantity\":1}")"
check "adding an unknown product returns 404" "404" "$STATUS"

# Ordering more than is in stock: the cart accepts it, checkout rejects it with 409.
request GET /api/products >/dev/null
SCARCE_ID="$(jget "min(d, key=lambda p: p['stockQuantity'])['id']")"
SCARCE_STOCK="$(jget "min(d, key=lambda p: p['stockQuantity'])['stockQuantity']")"
OVER_STOCK=$((SCARCE_STOCK + 1))

request POST /api/cart/items "{\"productId\":$SCARCE_ID,\"quantity\":$OVER_STOCK}" >/dev/null
STATUS="$(request POST /api/orders)"
check "ordering $OVER_STOCK of a product with stock $SCARCE_STOCK returns 409" "409" "$STATUS"
check "409 body carries the status field" "409" "$(jget "d['status']")"

request GET "/api/products/$SCARCE_ID" >/dev/null
check "failed checkout did not touch stock" "$SCARCE_STOCK" "$(jget "d['stockQuantity']")"

request DELETE "/api/cart/items/$SCARCE_ID" >/dev/null
STATUS="$(request POST /api/orders)"
check "checking out an empty cart returns 409" "409" "$STATUS"

STATUS="$(request DELETE /api/cart/items/999999)"
check "removing a product that is not in the cart returns 404" "404" "$STATUS"

# --------------------------------------------------------------------------------------------
# 3. API documentation (Phase 3)
# --------------------------------------------------------------------------------------------
section "API documentation"

STATUS="$(request GET /v3/api-docs)"
check "GET /v3/api-docs returns 200" "200" "$STATUS"
check "the spec is titled EcomDemo API" "EcomDemo API" "$(jget "d['info']['title']")"

# Every path the API serves must appear in the spec. Listed explicitly rather than derived from
# the spec itself, so that an endpoint springdoc fails to pick up is caught instead of ignored.
API_PATHS="/api/products /api/products/{id} /api/cart /api/cart/items /api/cart/items/{productId} /api/orders /api/orders/{id}"
for path in $API_PATHS; do
    check "the spec documents $path" "True" "$(jget "'$path' in d['paths']")"
done

check "every operation has a summary" "True" \
    "$(jget "all('summary' in op for ops in d['paths'].values() for op in ops.values())")"
# chr(36) is "$": the JSON key is "$ref", and writing it literally would be eaten by the shell
# on its way through jget's python -c.
check "every error response uses the ApiError schema" "True" \
    "$(jget "all(r['content']['application/json']['schema'][chr(36) + 'ref'].endswith('/ApiError') for ops in d['paths'].values() for op in ops.values() for c, r in op['responses'].items() if c >= '400')")"

# curl does not follow redirects here, so a 302 to /swagger-ui/index.html is the raw status.
STATUS="$(request GET /swagger-ui.html)"
case "$STATUS" in
    200 | 30*) pass "GET /swagger-ui.html returns 200 or a redirect (got $STATUS)" ;;
    *) fail "GET /swagger-ui.html returns 200 or a redirect" "200 or 3xx" "$STATUS" ;;
esac

STATUS="$(request GET /swagger-ui/index.html)"
check "the Swagger UI page itself returns 200" "200" "$STATUS"

STATUS="$(request GET /swagger-ui/swagger-ui-bundle.js)"
check "the Swagger UI javascript bundle is served" "200" "$STATUS"

# --------------------------------------------------------------------------------------------
# 4. Persistence across restarts (Phase 4)
# --------------------------------------------------------------------------------------------
#
# Proving that data outlives the process needs two runs with a restart between them, because a
# single run cannot restart the application it is talking to. So each run does two things:
#
#   1. checks whether the probe product the PREVIOUS run created is still there, and
#   2. leaves a fresh probe behind for the next run.
#
# With the old in-memory H2 the first check failed, because the restart wiped the database. To see
# it for yourself:
#
#   scripts/smoke-test.sh          # first run: leaves a probe, nothing to verify yet
#   <restart the application>
#   scripts/smoke-test.sh          # second run: the probe from before the restart is still there
#
# The probe id is remembered in a small state file, ignored by Git, NOT in the database - reading
# it back out of the API is the whole point of the check.
section "Persistence across restarts"

STATE_FILE="${SMOKE_STATE_FILE:-.smoke-state}"

PROBE_ID=""
PROBE_NAME=""
PROBE_PRICE=""

if [ -f "$STATE_FILE" ]; then
    # shellcheck disable=SC1090
    . "$STATE_FILE"
    STATUS="$(request GET "/api/products/${PROBE_ID:-0}")"
    if [ "$STATUS" = "200" ]; then
        pass "the probe product from the previous run is still there (id=$PROBE_ID)"
        check "it kept its name" "${PROBE_NAME:-<missing>}" "$(jget "d['name']")"
        check "it kept its price" "${PROBE_PRICE:-<missing>}" "$(jget "str(d['price'])")"
    else
        fail "the probe product from the previous run is still there (id=$PROBE_ID)" \
            "200" "$STATUS - the data did not survive; is the database still in memory?"
    fi
    # Tidy up, so repeated runs do not grow the catalogue.
    request DELETE "/api/products/$PROBE_ID" >/dev/null
else
    pass "first run: no previous probe to check (run again after a restart to verify persistence)"
fi

# Leave a probe for the next run. The name is unique per run so a stale row is never mistaken for
# a fresh one.
PROBE_NAME="Persistence Probe $(date +%Y%m%d-%H%M%S)"
PROBE_PRICE="4242.42"
STATUS="$(request POST /api/products \
    "{\"name\":\"$PROBE_NAME\",\"description\":\"written by the smoke test to survive a restart\",\"price\":$PROBE_PRICE,\"stockQuantity\":1}")"
check "a probe product is created for the next run" "201" "$STATUS"
PROBE_ID="$(jget "d['id']")"

STATUS="$(request GET "/api/products/$PROBE_ID")"
check "the probe reads back from the database" "200" "$STATUS"

# Quoted, because the name contains spaces and this file is read back with `.` (source).
printf "PROBE_ID='%s'\nPROBE_NAME='%s'\nPROBE_PRICE='%s'\n" \
    "$PROBE_ID" "$PROBE_NAME" "$PROBE_PRICE" > "$STATE_FILE"
pass "probe id $PROBE_ID recorded in $STATE_FILE for the next run"

# --------------------------------------------------------------------------------------------
# 5. Flyway migrations (Phase 5)
# --------------------------------------------------------------------------------------------
#
# Two things to prove: that the schema this application is running on was built by Flyway and
# recorded, and that migration V3's new column reaches the API.
#
# The history check needs SQL, not HTTP, so it wants a psql. It uses one on PATH if there is one,
# otherwise it runs psql inside the PostgreSQL container (POSTGRES_CONTAINER, default
# ecomdemo-postgres). With neither, the check is SKIPPED and says so - never silently passed.
section "Flyway migrations"

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-ecomdemo-postgres}"
PGDB="${POSTGRES_DB:-ecomdemo}"
PGUSER_="${POSTGRES_USER:-ecomdemo}"

# psql_query <sql> -> prints the result, one row per line, no headers or padding
psql_query() {
    if command -v psql >/dev/null 2>&1; then
        PGPASSWORD="${POSTGRES_PASSWORD:-ecomdemo}" psql -qtAX \
            -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" \
            -U "$PGUSER_" -d "$PGDB" -c "$1" 2>/dev/null
    elif command -v docker >/dev/null 2>&1 \
        && docker exec "$POSTGRES_CONTAINER" true >/dev/null 2>&1; then
        docker exec "$POSTGRES_CONTAINER" psql -qtAX -U "$PGUSER_" -d "$PGDB" -c "$1" 2>/dev/null
    else
        return 1
    fi
}

if HISTORY="$(psql_query \
    "SELECT version || ':' || CASE WHEN success THEN 'ok' ELSE 'FAILED' END \
     FROM flyway_schema_history WHERE version IS NOT NULL \
     ORDER BY installed_rank;" | tr -d '\r' | paste -sd, -)"; then
    check "flyway_schema_history shows V1-V4, all successful" "1:ok,2:ok,3:ok,4:ok" "$HISTORY"

    PENDING="$(psql_query \
        "SELECT count(*) FROM flyway_schema_history WHERE success = false;" | tr -d '\r ')"
    check "no migration is recorded as failed" "0" "$PENDING"

    CATEGORY_INDEX="$(psql_query \
        "SELECT count(*) FROM pg_indexes \
         WHERE schemaname = 'public' AND indexname = 'idx_product_category';" | tr -d '\r ')"
    check "V3's idx_product_category index exists" "1" "$CATEGORY_INDEX"

    AUDIT_TABLE="$(psql_query \
        "SELECT count(*) FROM information_schema.tables \
         WHERE table_schema = 'public' AND table_name = 'order_audit';" | tr -d '\r ')"
    check "V4's order_audit table exists" "1" "$AUDIT_TABLE"

    VERSION_COLUMN="$(psql_query \
        "SELECT count(*) FROM information_schema.columns \
         WHERE table_name = 'product' AND column_name = 'version';" | tr -d '\r ')"
    check "V4's product.version column exists" "1" "$VERSION_COLUMN"
else
    skip "flyway_schema_history shows V1-V4, all successful" \
        "no psql on PATH and no running container named '$POSTGRES_CONTAINER'"
    skip "no migration is recorded as failed" "same as above"
    skip "V3's idx_product_category index exists" "same as above"
    skip "V4's order_audit table exists" "same as above"
    skip "V4's product.version column exists" "same as above"
fi

# The column V3 added has to reach the API, not just the database. The seeded product is looked
# up by name rather than by id, so the check does not depend on which ids happen to be free.
STATUS="$(request GET /api/products)"
check "GET /api/products returns 200" "200" "$STATUS"
check "every product in the list carries a category field" "True" \
    "$(jget "all('category' in p for p in d)")"
check "the seeded keyboard was backfilled by V3" "PERIPHERALS" \
    "$(jget "next(p['category'] for p in d if p['name'] == 'Mechanical Keyboard')")"

# A category can be set through the API on the way in, and comes back out again.
STATUS="$(request POST /api/products \
    '{"name":"Category Probe","description":"created with a category","price":10.00,"stockQuantity":1,"category":"STORAGE"}')"
check "a product can be created with a category" "201" "$STATUS"
CATEGORISED_ID="$(jget "d['id']")"
check "and the category comes back" "STORAGE" "$(jget "d['category']")"

# The column is nullable ON PURPOSE: V3 had to stay safe for instances of the old application
# version that were still inserting products while it ran. A create without a category must work.
STATUS="$(request POST /api/products \
    '{"name":"Uncategorised Probe","description":"created without a category","price":10.00,"stockQuantity":1}')"
check "a product can be created without one" "201" "$STATUS"
UNCATEGORISED_ID="$(jget "d['id']")"
check "and comes back with category null" "True" "$(jget "d['category'] is None")"

request DELETE "/api/products/$CATEGORISED_ID" >/dev/null
request DELETE "/api/products/$UNCATEGORISED_ID" >/dev/null
pass "the two category probe products are cleaned up"

# --------------------------------------------------------------------------------------------
# Transactions and concurrency (Phase 6)
# --------------------------------------------------------------------------------------------
# The oversell race, against the running application and a real PostgreSQL rather than a test
# harness and H2. A product with exactly one unit in stock, two checkouts of the shared cart
# fired at the same moment, and only one of them may come back with a 201. Before this phase both
# would: each read stock 1, each decided there was enough, and each wrote 0.
section "Transactions and concurrency"

STATUS="$(request POST /api/products \
    '{"name":"Race Probe","description":"one unit, two buyers","price":25.00,"stockQuantity":1,"category":"TEST"}')"
check "a product with exactly one unit in stock is created" "201" "$STATUS"
RACE_ID="$(jget "d['id']")"

request DELETE "/api/cart/items/$RACE_ID" >/dev/null
STATUS="$(request POST /api/cart/items "{\"productId\":$RACE_ID,\"quantity\":1}")"
check "the last unit is in the cart" "200" "$STATUS"

# One curl, two transfers, --parallel-immediate so it starts the second without waiting for the
# first. Two backgrounded curls would also work, but each pays ~10ms of process start-up while a
# checkout takes ~5ms, so the second usually arrives after the first has already committed - the
# statuses would still be right, for the wrong reason. Inside one process the two requests really
# do overlap, and the application log shows the versioned UPDATE being rejected.
# -o is given twice because -o applies to one transfer each; -w prints per transfer.
RACE_RESULT="$(curl -sS --parallel --parallel-immediate -X POST \
    -o /dev/null -o /dev/null -w '%{http_code}\n' \
    "$BASE_URL/api/orders" "$BASE_URL/api/orders" | sort | paste -sd, -)"
check "two simultaneous checkouts return exactly one 201 and one 409" "201,409" "$RACE_RESULT"

request GET "/api/products/$RACE_ID" >/dev/null
check "the one unit was sold once, so stock is 0" "0" "$(jget "d['stockQuantity']")"

request GET /api/orders >/dev/null
check "exactly one order holds that product" "1" \
    "$(jget "sum(1 for o in d for i in o['items'] if i['productId'] == $RACE_ID)")"

request GET /api/cart >/dev/null
check "the cart is empty after the race" "0" "$(jget "len(d['items'])")"

# The loser's transaction rolled back everything it had done, including emptying the cart - and
# then found the cart already empty on its retry, which is the 409 it returned.
if AUDIT="$(psql_query \
    "SELECT outcome FROM order_audit ORDER BY id DESC LIMIT 2;" | tr -d '\r ' | sort | paste -sd, -)"; then
    check "the race left one PLACED and one REJECTED audit row" "PLACED,REJECTED" "$AUDIT"

    ORPHANS="$(psql_query \
        "SELECT count(*) FROM order_audit WHERE outcome = 'REJECTED' AND order_id IS NOT NULL;" \
        | tr -d '\r ')"
    check "no rejected attempt claims to have created an order" "0" "$ORPHANS"
else
    skip "the race left one PLACED and one REJECTED audit row" \
        "no psql on PATH and no running container named '$POSTGRES_CONTAINER'"
    skip "no rejected attempt claims to have created an order" "same as above"
fi

# An order that is refused on its merits must leave nothing behind either: the cart is empty now,
# so this checkout is rejected before it writes anything.
STATUS="$(request POST /api/orders)"
check "checking out an empty cart returns 409" "409" "$STATUS"
check "and says why" "True" "$(jget "'cart is empty' in d['message']")"

request DELETE "/api/products/$RACE_ID" >/dev/null
pass "the race probe product is cleaned up"

# --------------------------------------------------------------------------------------------
# Summary
# --------------------------------------------------------------------------------------------
printf '\n\033[1mSummary:\033[0m %d passed, %d failed, %d skipped\n' \
    "$PASSED" "$FAILED" "$SKIPPED"

if [ "$SKIPPED" -gt 0 ]; then
    printf '\033[33mNote: %d check(s) could not be run - see the SKIP lines above.\033[0m\n' \
        "$SKIPPED"
fi

if [ "$FAILED" -gt 0 ]; then
    printf '\033[31mSMOKE TEST FAILED\033[0m\n'
    exit 1
fi

printf '\033[32mSMOKE TEST PASSED\033[0m\n'
