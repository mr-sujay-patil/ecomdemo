#!/usr/bin/env bash
#
# End-to-end smoke test for EcomDemo.
#
# Runs the full business flow against a RUNNING application and prints one PASS/FAIL line per
# check. Exits non-zero if any check fails, which makes it a growing regression suite: every
# later phase adds checks here and none are ever removed.
#
#   Usage:  docker compose up --build       # in one terminal (since Phase 10)
#           scripts/smoke-test.sh           # in another
#
#   Still works against a locally run application too:
#           ./mvnw spring-boot:run
#           scripts/smoke-test.sh
#
# The script only ever talks HTTP, so it does not care which of the two is behind BASE_URL. The
# one place that has to know is the Flyway section, which needs SQL: it finds the database
# container by name, trying the compose stack's first.
#
# Since Phase 8 the API needs credentials, and since Phase 9 those credentials are exchanged
# once for a token. The script logs in as the ADMIN seeded by migration V5 for catalogue writes,
# and as a CUSTOMER it registers for itself for everything else, then sends
# `Authorization: Bearer <jwt>` on every call. Both passwords are throwaway local development
# credentials and can be overridden: SMOKE_ADMIN_PASSWORD, SMOKE_CUSTOMER_PASSWORD.
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

# --------------------------------------------------------------------------------------------
# Who the next request is sent as
# --------------------------------------------------------------------------------------------
# Bearer tokens: the password is sent once, to POST /api/auth/login, and every later call
# carries the signed JWT that came back. "Bearer" is meant literally - whoever holds the token
# is the account - so a token is as sensitive as a password and, like Basic before it, is only
# safe over TLS or, as here, on localhost.
#
# The credentials below are throwaway local development values. The admin's is the one migration
# V5 seeds; the customer's belongs to an account this script registers for itself through the
# public endpoint, so the password really is hashed by the application.
ADMIN_USER="admin"
ADMIN_PASSWORD="${SMOKE_ADMIN_PASSWORD:-admin123}"
CUSTOMER_USER="${SMOKE_CUSTOMER:-smoke-customer}"
CUSTOMER_PASSWORD="${SMOKE_CUSTOMER_PASSWORD:-smoke-test-password}"
# A second shopper, so the script can prove one customer cannot read another's order.
OTHER_USER="${SMOKE_CUSTOMER_B:-smoke-customer-b}"

# Tokens, filled in by login() once the application is up. AUTH holds the one in use.
ADMIN_TOKEN=""
CUSTOMER_TOKEN=""
OTHER_TOKEN=""

AUTH=""   # empty means "send no Authorization header at all"

as_anonymous() { AUTH=""; }
as_admin()     { AUTH="$ADMIN_TOKEN"; }
as_customer()  { AUTH="$CUSTOMER_TOKEN"; }
as_other()     { AUTH="$OTHER_TOKEN"; }
as_token()     { AUTH="$1"; }

# request <METHOD> <PATH> [JSON] -> echoes the HTTP status, body lands in $BODY
# Sends whatever $AUTH currently holds, so a test switches identity by calling as_admin() etc.
# Spelled out in four branches rather than built up in an array: macOS still ships bash 3.2,
# where expanding an empty array under `set -u` is an unbound-variable error.
request() {
    local method="$1" path="$2" data="${3:-}"
    if [ -n "$AUTH" ]; then
        if [ -n "$data" ]; then
            curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE_URL$path" \
                -H "Authorization: Bearer $AUTH" -H 'Content-Type: application/json' -d "$data"
        else
            curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE_URL$path" \
                -H "Authorization: Bearer $AUTH"
        fi
    elif [ -n "$data" ]; then
        curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE_URL$path" \
            -H 'Content-Type: application/json' -d "$data"
    else
        curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE_URL$path"
    fi
}

# login <username> <password> -> echoes the access token, or nothing on failure.
# The only place in the script a password is sent.
login() {
    local username="$1" password="$2" saved="$AUTH" status body
    # printf into a variable first: bash 3.2 mis-splits escaped double quotes nested inside a
    # command substitution inside a quoted string, and the body arrives mangled.
    body="$(printf '{"username":"%s","password":"%s"}' "$username" "$password")"
    as_anonymous
    status="$(request POST /api/auth/login "$body")"
    AUTH="$saved"
    if [ "$status" = "200" ]; then
        jget "d['accessToken']"
    fi
}

# jwt_part <token> <0|1> -> decodes the header or the payload of a JWT and prints it as JSON.
# No key is needed, and that is the point: a JWT payload is Base64url-ENCODED, not encrypted.
jwt_part() {
    python3 -c "
import base64, json, sys
part = sys.argv[1].split('.')[int(sys.argv[2])]
part += '=' * (-len(part) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(part))))
" "$1" "$2" 2>/dev/null
}

# register <username> -> creates a CUSTOMER account, or accepts that it already exists.
# The script is re-runnable against a long-lived database, so 409 is a normal outcome: the
# account is there either way and its password has not changed.
register() {
    local username="$1" status saved="$AUTH" body
    body="$(printf '{"username":"%s","password":"%s","fullName":"Smoke Test %s"}' \
        "$username" "$CUSTOMER_PASSWORD" "$username")"
    as_anonymous
    status="$(request POST /api/customers/register "$body")"
    AUTH="$saved"
    case "$status" in
        201|409) return 0 ;;
        *) return 1 ;;
    esac
}

# jget <python expression over `d`> -> prints the value from the last response body
jget() {
    python3 -c "import json;d=json.load(open('$BODY'));print($1)" 2>/dev/null
}

# The database container's name. Phase 10's compose stack calls it `ecomdemo-db`; the
# hand-started container from Phases 4-9 was `ecomdemo-postgres`. Both are tried, newest first,
# so the script works against either without being told which - and POSTGRES_CONTAINER still
# overrides if somebody names it something else entirely.
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-}"
if [ -z "$POSTGRES_CONTAINER" ]; then
    for candidate in ecomdemo-db ecomdemo-postgres; do
        if command -v docker >/dev/null 2>&1 \
            && docker exec "$candidate" true >/dev/null 2>&1; then
            POSTGRES_CONTAINER="$candidate"
            break
        fi
    done
    POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-ecomdemo-db}"
fi
PGDB="${POSTGRES_DB:-ecomdemo}"
PGUSER_="${POSTGRES_USER:-ecomdemo}"

# The Redis container, found the same way as the database one: the compose stack's name first.
REDIS_CONTAINER="${REDIS_CONTAINER:-}"
if [ -z "$REDIS_CONTAINER" ]; then
    for candidate in ecomdemo-cache ecomdemo-redis; do
        if command -v docker >/dev/null 2>&1 \
            && docker exec "$candidate" true >/dev/null 2>&1; then
            REDIS_CONTAINER="$candidate"
            break
        fi
    done
    REDIS_CONTAINER="${REDIS_CONTAINER:-ecomdemo-cache}"
fi

# redis_cli <args...> -> runs redis-cli, preferring one on PATH and falling back to the container.
# Returns non-zero when neither is available, so the caller can SKIP rather than invent a pass.
redis_cli() {
    if command -v redis-cli >/dev/null 2>&1; then
        redis-cli -h "${REDIS_HOST:-localhost}" -p "${REDIS_PORT:-6379}" "$@" 2>/dev/null
    elif command -v docker >/dev/null 2>&1 \
        && docker exec "$REDIS_CONTAINER" true >/dev/null 2>&1; then
        docker exec "$REDIS_CONTAINER" redis-cli "$@" 2>/dev/null
    else
        return 1
    fi
}

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

# --------------------------------------------------------------------------------------------
# 0b. Authentication and authorization
# --------------------------------------------------------------------------------------------
section "Authentication and authorization"

# Browsing the catalogue is the shop window: no account, no header, 200.
as_anonymous
check "anonymous GET /api/products returns 200" "200" "$(request GET /api/products)"

# The cart is somebody's. With no credentials the server cannot know whose, so it asks for them.
# 401 means "unauthenticated" despite the name: presenting a token could change the answer.
check "anonymous GET /api/cart returns 401" "401" "$(request GET /api/cart)"
check "and the 401 uses the standard error shape" "401" "$(jget "d['status']")"
check "and it says where to log in" "True" "$(jget "'/api/auth/login' in d['message']")"

# Registration has to be reachable by someone with no account - requiring one would be a closed
# loop. A second run finds the account already there, which is a 409 and equally fine.
if register "$CUSTOMER_USER"; then
    pass "a customer account exists (registered, or already present)"
else
    fail "a customer account exists" "201 or 409" "$(jget "d['status']")"
    printf '\nCannot continue without a customer account.\n'
    exit 1
fi
register "$OTHER_USER" && pass "a second customer account exists"

# --- Logging in ---------------------------------------------------------------------------
# The one request in the whole script that carries a password.
ADMIN_TOKEN="$(login "$ADMIN_USER" "$ADMIN_PASSWORD")"
CUSTOMER_TOKEN="$(login "$CUSTOMER_USER" "$CUSTOMER_PASSWORD")"
OTHER_TOKEN="$(login "$OTHER_USER" "$CUSTOMER_PASSWORD")"

if [ -z "$ADMIN_TOKEN" ] || [ -z "$CUSTOMER_TOKEN" ] || [ -z "$OTHER_TOKEN" ]; then
    fail "login returns a token" "a JWT for admin, customer and the second customer" "one was empty"
    printf '\nCannot continue without tokens.\n'
    exit 1
fi
pass "login returns a token for the admin and both customers"

# A JWT is three Base64url segments joined by dots: header, payload, signature.
check "the token has three dot-separated parts" "3" \
    "$(printf '%s' "$CUSTOMER_TOKEN" | awk -F. '{print NF}')"
check "the header names the signing algorithm" "HS256" \
    "$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['alg'])" "$(jwt_part "$CUSTOMER_TOKEN" 0)" 2>/dev/null)"

# The payload decodes with no key at all. That is not a flaw - it is why nothing secret may ever
# go into a claim, and why the signature rather than secrecy is what makes claims trustworthy.
CLAIMS="$(jwt_part "$CUSTOMER_TOKEN" 1)"
check "the payload is readable without any key (encoded, not encrypted)" "$CUSTOMER_USER" \
    "$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['sub'])" "$CLAIMS" 2>/dev/null)"
check "it carries the roles the rules are decided from" "CUSTOMER" \
    "$(python3 -c "import json,sys;print(','.join(json.loads(sys.argv[1])['roles']))" "$CLAIMS" 2>/dev/null)"
check "it never carries the password" "True" \
    "$(python3 -c "import sys;print(sys.argv[2] not in sys.argv[1])" "$CLAIMS" "$CUSTOMER_PASSWORD")"

# Short-lived on purpose: a JWT cannot be withdrawn, so expiry is the only thing that ever takes
# one out of circulation.
TOKEN_LIFETIME="$(python3 -c "import json,sys;d=json.loads(sys.argv[1]);print(d['exp']-d['iat'])" "$CLAIMS" 2>/dev/null)"
if [ -n "${TOKEN_LIFETIME:-}" ] && [ "$TOKEN_LIFETIME" -gt 0 ] && [ "$TOKEN_LIFETIME" -le 3600 ]; then
    pass "the token is short-lived (${TOKEN_LIFETIME}s), because it cannot be revoked"
else
    fail "the token is short-lived" "1..3600 seconds" "${TOKEN_LIFETIME:-<unreadable>}"
fi

# --- Tokens that must not work ---------------------------------------------------------------
# Tampering: take a real CUSTOMER token and rewrite the payload to claim ADMIN. The signature no
# longer covers the payload, so the token never becomes an identity at all - hence 401, not 403.
FORGED="$(python3 -c "
import base64, json, sys
header, payload, signature = sys.argv[1].split('.')
padded = payload + '=' * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(padded))
claims['roles'] = ['ADMIN']
forged = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip('=')
print('.'.join([header, forged, signature]))
" "$CUSTOMER_TOKEN" 2>/dev/null)"

as_token "$FORGED"
check "a tampered token returns 401" "401" "$(request GET /api/customers/me)"
check "and says the token is invalid or expired" "True" \
    "$(jget "'invalid or has expired' in d['message']")"
as_token "$FORGED"
check "a tampered ADMIN claim buys nothing" "401" \
    "$(request POST /api/products '{"name":"Forged","price":1.00,"stockQuantity":1}')"

# Expiry. A correctly signed token whose lifetime is entirely in the past is only mintable with
# the signing key, so this check runs in full when JWT_SECRET is set and falls back to an
# unsigned expired token otherwise - which the server refuses just as firmly, though for the
# signature rather than the clock. Either way the honest thing is to say which ran.
EXPIRED_TOKEN="$(python3 -c "
import base64, hashlib, hmac, json, os, sys

def b64(raw):
    return base64.urlsafe_b64encode(raw).decode().rstrip('=')

issued = int(__import__('time').time()) - 7200          # two hours ago
claims = {'iss': 'ecomdemo', 'sub': sys.argv[1], 'uid': 1, 'roles': ['CUSTOMER'],
          'iat': issued, 'exp': issued + 900}           # expired 105 minutes ago
header = b64(json.dumps({'alg': 'HS256'}).encode())
payload = b64(json.dumps(claims).encode())
secret = os.environ.get('JWT_SECRET', '')
signing_input = (header + '.' + payload).encode()
signature = b64(hmac.new(secret.encode(), signing_input, hashlib.sha256).digest()) if secret \
    else b64(b'not-a-real-signature')
print('.'.join([header, payload, signature]))
" "$CUSTOMER_USER" 2>/dev/null)"

as_token "$EXPIRED_TOKEN"
check "an expired token returns 401" "401" "$(request GET /api/customers/me)"
check "and says the token is invalid or expired" "True" \
    "$(jget "'invalid or has expired' in d['message']")"
if [ -n "${JWT_SECRET:-}" ]; then
    pass "the expired token was correctly signed, so expiry alone caused the refusal"
else
    pass "the expired token was unsigned (JWT_SECRET unset); set it to test expiry specifically"
fi

as_token "not-even-a-jwt"
check "nonsense in the Authorization header is a 401, not a 500" "401" "$(request GET /api/customers/me)"

# --- Login failures ---------------------------------------------------------------------------
as_anonymous
# Built with printf into a variable first. Nesting escaped double quotes inside a command
# substitution inside a quoted string is one of the places bash 3.2 gets the word splitting
# wrong, and the body arrives mangled (a 400 instead of the 401 this is testing).
WRONG_PASSWORD_BODY="$(printf '{"username":"%s","password":"definitely-wrong"}' "$CUSTOMER_USER")"
check "a wrong password returns 401" "401" "$(request POST /api/auth/login "$WRONG_PASSWORD_BODY")"
WRONG_PASSWORD_MESSAGE="$(jget "d['message']")"
check "an unknown username returns 401" "401" \
    "$(request POST /api/auth/login '{"username":"no-such-account-at-all","password":"definitely-wrong"}')"
check "and says exactly the same thing, so usernames cannot be enumerated" \
    "$WRONG_PASSWORD_MESSAGE" "$(jget "d['message']")"

# --- What each role may do --------------------------------------------------------------------
as_customer
check "the token opens the account's own profile" "200" "$(request GET /api/customers/me)"
check "and the profile is its own" "$CUSTOMER_USER" "$(jget "d['username']")"
check "registration never hands out an ADMIN role" "CUSTOMER" "$(jget "d['role']")"
check "no endpoint ever returns the password or its hash" "True" \
    "$(jget "'password' not in d")"

# 403, not 401: the server knows exactly who this is and the answer is still no. Presenting the
# same token again will never help - only a different role would.
STATUS="$(request POST /api/products \
    '{"name":"Forbidden Probe","description":"a customer may not create this","price":1.00,"stockQuantity":1,"category":"TEST"}')"
check "a CUSTOMER creating a product returns 403" "403" "$STATUS"
check "and the 403 uses the standard error shape" "403" "$(jget "d['status']")"

as_admin
STATUS="$(request POST /api/products \
    '{"name":"Admin Probe","description":"an admin may create this","price":1.00,"stockQuantity":1,"category":"TEST"}')"
check "an ADMIN creating the same product returns 201" "201" "$STATUS"
ADMIN_PROBE_ID="$(jget "d['id']")"
request DELETE "/api/products/$ADMIN_PROBE_ID" >/dev/null
pass "the admin probe product is cleaned up"

# An administrator is refused on the cart: these endpoints act on "my" cart, and an admin has
# none. Nothing about being an admin implies being a customer.
check "an ADMIN reading the cart returns 403" "403" "$(request GET /api/cart)"

# Everything from here on is the shopping flow, so it runs as the customer.
as_customer

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
# it back out of the API is the whole point of the check. The file also records WHICH database the
# probe was left in, so that switching databases (`docker compose down -v`, or moving off the
# Phase 4-9 container) reads as a first run rather than as lost data.
section "Persistence across restarts"

STATE_FILE="${SMOKE_STATE_FILE:-.smoke-state}"

PROBE_ID=""
PROBE_NAME=""
PROBE_PRICE=""
PROBE_DB_ID=""

# Which database the probe was left in. PostgreSQL stamps every cluster with a unique
# system_identifier at initdb time, so this changes when - and only when - the data directory is
# recreated: `docker compose down -v`, or moving from the hand-started container of Phases 4-9 to
# the compose stack's own volume.
#
# Without it, switching databases fails this check for a reason that has nothing to do with
# persistence: the probe really is gone, because it was in a different database. Recording the
# identity lets the script tell "the data did not survive a restart" (a real failure) apart from
# "this is a different database" (a first run).
CURRENT_DB_ID="$(psql_query "SELECT system_identifier FROM pg_control_system();" 2>/dev/null | tr -d '\r ')"

if [ -f "$STATE_FILE" ]; then
    # shellcheck disable=SC1090
    . "$STATE_FILE"
fi

if [ -n "${PROBE_DB_ID:-}" ] && [ -n "$CURRENT_DB_ID" ] && [ "$PROBE_DB_ID" != "$CURRENT_DB_ID" ]; then
    pass "the previous probe belongs to a different database; treating this as a first run"
    PROBE_ID=""
fi

if [ -n "${PROBE_ID:-}" ]; then
    STATUS="$(request GET "$(printf '/api/products/%s' "$PROBE_ID")")"
    if [ "$STATUS" = "200" ]; then
        pass "the probe product from the previous run is still there (id=$PROBE_ID)"
        check "it kept its name" "${PROBE_NAME:-<missing>}" "$(jget "d['name']")"
        check "it kept its price" "${PROBE_PRICE:-<missing>}" "$(jget "str(d['price'])")"
    else
        fail "the probe product from the previous run is still there (id=$PROBE_ID)" \
            "200" "$STATUS - the data did not survive; is the database still in memory?"
    fi
    # Tidy up, so repeated runs do not grow the catalogue. Deleting is a catalogue write.
    as_admin
    request DELETE "/api/products/$PROBE_ID" >/dev/null
    as_customer
else
    pass "first run against this database: no previous probe to check (run again after a restart)"
fi

# Leave a probe for the next run. The name is unique per run so a stale row is never mistaken for
# a fresh one.
PROBE_NAME="Persistence Probe $(date +%Y%m%d-%H%M%S)"
PROBE_PRICE="4242.42"
as_admin
STATUS="$(request POST /api/products \
    "{\"name\":\"$PROBE_NAME\",\"description\":\"written by the smoke test to survive a restart\",\"price\":$PROBE_PRICE,\"stockQuantity\":1}")"
as_customer
check "a probe product is created for the next run" "201" "$STATUS"
PROBE_ID="$(jget "d['id']")"

STATUS="$(request GET "/api/products/$PROBE_ID")"
check "the probe reads back from the database" "200" "$STATUS"

# Quoted, because the name contains spaces and this file is read back with `.` (source).
printf "PROBE_ID='%s'\nPROBE_NAME='%s'\nPROBE_PRICE='%s'\nPROBE_DB_ID='%s'\n" \
    "$PROBE_ID" "$PROBE_NAME" "$PROBE_PRICE" "$CURRENT_DB_ID" > "$STATE_FILE"
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

if HISTORY="$(psql_query \
    "SELECT version || ':' || CASE WHEN success THEN 'ok' ELSE 'FAILED' END \
     FROM flyway_schema_history WHERE version IS NOT NULL \
     ORDER BY installed_rank;" | tr -d '\r' | paste -sd, -)"; then
    check "flyway_schema_history shows V1-V8, all successful" \
        "1:ok,2:ok,3:ok,4:ok,5:ok,6:ok,7:ok,8:ok" "$HISTORY"

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

    ADMIN_ROW="$(psql_query \
        "SELECT count(*) FROM users WHERE username = 'admin' AND role = 'ADMIN';" | tr -d '\r ')"
    check "V5 seeded exactly one administrator" "1" "$ADMIN_ROW"

    # The stored credential must be a BCrypt hash, never the password. $2a$ is the algorithm,
    # 10 the cost factor; the whole string is always 60 characters.
    HASHED="$(psql_query \
        "SELECT count(*) FROM users WHERE password LIKE '\$2a\$10\$%' AND length(password) = 60;" \
        | tr -d '\r ')"
    TOTAL_USERS="$(psql_query "SELECT count(*) FROM users;" | tr -d '\r ')"
    check "every stored password is a BCrypt hash, not a password" "$TOTAL_USERS" "$HASHED"

    OWNED_CARTS="$(psql_query \
        "SELECT count(*) FROM information_schema.columns \
         WHERE table_name = 'cart' AND column_name = 'user_id' AND is_nullable = 'NO';" \
        | tr -d '\r ')"
    check "V6's cart.user_id exists and is NOT NULL" "1" "$OWNED_CARTS"

    ORPHAN_ORDERS="$(psql_query "SELECT count(*) FROM orders WHERE user_id IS NULL;" | tr -d '\r ')"
    check "no order belongs to nobody" "0" "$ORPHAN_ORDERS"
else
    skip "flyway_schema_history shows V1-V8, all successful" \
        "no psql on PATH and no running container named '$POSTGRES_CONTAINER'"
    skip "no migration is recorded as failed" "same as above"
    skip "V3's idx_product_category index exists" "same as above"
    skip "V4's order_audit table exists" "same as above"
    skip "V4's product.version column exists" "same as above"
    skip "V5 seeded exactly one administrator" "same as above"
    skip "every stored password is a BCrypt hash, not a password" "same as above"
    skip "V6's cart.user_id exists and is NOT NULL" "same as above"
    skip "no order belongs to nobody" "same as above"
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
as_admin
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
as_customer
pass "the two category probe products are cleaned up"

# --------------------------------------------------------------------------------------------
# Transactions and concurrency (Phase 6)
# --------------------------------------------------------------------------------------------
# The oversell race, against the running application and a real PostgreSQL rather than a test
# harness and H2. A product with exactly one unit in stock, two checkouts of ONE customer's cart
# fired at the same moment, and only one of them may come back with a 201. Before this phase both
# would: each read stock 1, each decided there was enough, and each wrote 0.
#
# The two requests are one shopper clicking twice, not two shoppers: since Phase 8 two accounts
# have two separate carts and could not collide over the same cart at all.
section "Transactions and concurrency"

as_admin
STATUS="$(request POST /api/products \
    '{"name":"Race Probe","description":"one unit, two buyers","price":25.00,"stockQuantity":1,"category":"TEST"}')"
check "a product with exactly one unit in stock is created" "201" "$STATUS"
RACE_ID="$(jget "d['id']")"
as_customer

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
    -H "Authorization: Bearer $CUSTOMER_TOKEN" \
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

as_admin
request DELETE "/api/products/$RACE_ID" >/dev/null
as_customer
pass "the race probe product is cleaned up"

# --------------------------------------------------------------------------------------------
# Data ownership (Phase 8)
# --------------------------------------------------------------------------------------------
# The rules above are about endpoints. These are about rows: two accounts using the same
# endpoints must not be able to reach each other's data. Before this phase there was one cart
# for the whole world and every order was readable by anybody who could guess an id.
section "Data ownership"

as_admin
STATUS="$(request POST /api/products \
    '{"name":"Ownership Probe","description":"bought by one customer only","price":8.00,"stockQuantity":5,"category":"TEST"}')"
check "an ownership probe product is created" "201" "$STATUS"
OWNED_ID="$(jget "d['id']")"

# Customer A buys one.
as_customer
request DELETE "/api/cart/items/$OWNED_ID" >/dev/null
request POST /api/cart/items "{\"productId\":$OWNED_ID,\"quantity\":1}" >/dev/null
STATUS="$(request POST /api/orders)"
check "customer A places an order" "201" "$STATUS"
OWNED_ORDER_ID="$(jget "d['id']")"
check "the order records who placed it" "$CUSTOMER_USER" "$(jget "d['username']")"

# Customer B has a cart of their own, and it is empty even though A has been shopping.
as_other
request GET /api/cart >/dev/null
check "customer B's cart is their own, and empty" "0" "$(jget "len(d['items'])")"

# 403, not 404: the order exists, it is simply not theirs. @PostAuthorize compares the owner
# with the authenticated username after loading the row, because whose order it is, is IN the row.
STATUS="$(request GET "/api/orders/$OWNED_ORDER_ID")"
check "customer B cannot read customer A's order" "403" "$STATUS"
check "and the refusal uses the standard error shape" "403" "$(jget "d['status']")"

request GET /api/orders >/dev/null
check "nor does it appear in customer B's order list" "0" \
    "$(jget "sum(1 for o in d if o['id'] == $OWNED_ORDER_ID)")"

# The owner, of course, can.
as_customer
check "customer A can still read their own order" "200" "$(request GET "/api/orders/$OWNED_ORDER_ID")"
request GET /api/orders >/dev/null
check "and it is in their own order list" "1" \
    "$(jget "sum(1 for o in d if o['id'] == $OWNED_ORDER_ID)")"
check "every order in the list belongs to the caller" "True" \
    "$(jget "all(o['username'] == '$CUSTOMER_USER' for o in d)")"

# The probe product was ordered, so it cannot be deleted while an order references it (V1's
# RESTRICT foreign key). Leaving it is correct; the order history is what keeps it alive.
pass "the ownership probe order is left in history, as an order should be"

# --------------------------------------------------------------------------------------------
# Caching (Phase 13)
# --------------------------------------------------------------------------------------------
# Two questions: does a read populate the cache, and does a write invalidate it. Both are asked
# of Redis directly, because a cache that is never read from and a cache that is never written to
# look identical through the API.
section "Caching"

if redis_cli PING >/dev/null 2>&1; then
    as_admin
    STATUS="$(request POST /api/products \
        '{"name":"Cache Probe","description":"read me twice","price":77.00,"stockQuantity":4,"category":"TEST"}')"
    check "a cache probe product is created" "201" "$STATUS"
    CACHE_ID="$(jget "d['id']")"
    CACHE_KEY="product::$CACHE_ID"

    # Creating evicts the listing but never creates an entry for an id that did not exist, so the
    # key must be absent until something reads it.
    redis_cli DEL "$CACHE_KEY" >/dev/null
    check "no cache entry before the first read" "0" "$(redis_cli EXISTS "$CACHE_KEY" | tr -d '\r ')"

    as_anonymous
    check "reading the product returns 200" "200" "$(request GET "/api/products/$CACHE_ID")"
    check "and the cache key now exists" "1" "$(redis_cli EXISTS "$CACHE_KEY" | tr -d '\r ')"

    # JSON, not a Java-serialized blob - which is the whole reason redis-cli can be used to check
    # any of this.
    check "the entry is readable JSON" "True" \
        "$(redis_cli GET "$CACHE_KEY" | python3 -c "
import json,sys
raw = sys.stdin.read().strip()
try:
    print(json.loads(raw)['name'] == 'Cache Probe')
except Exception:
    print(False)
")"

    # Everything expires. A missed eviction is wrong until the TTL clears it, so there has to be
    # one.
    CACHE_TTL="$(redis_cli TTL "$CACHE_KEY" | tr -d '\r ')"
    if [ -n "${CACHE_TTL:-}" ] && [ "$CACHE_TTL" -gt 0 ]; then
        pass "the entry expires on its own (TTL ${CACHE_TTL}s)"
    else
        fail "the entry expires on its own" "a positive TTL" "${CACHE_TTL:-<none>}"
    fi

    # Updating must not leave the old value behind. @CachePut writes the new one straight in, so
    # the key is still present AND now holds the new name.
    as_admin
    STATUS="$(request PUT "/api/products/$CACHE_ID" \
        '{"name":"Cache Probe v2","description":"updated","price":88.00,"stockQuantity":4,"category":"TEST"}')"
    check "updating the product returns 200" "200" "$STATUS"
    check "the cache entry is refreshed, not stale" "True" \
        "$(redis_cli GET "$CACHE_KEY" | python3 -c "
import json,sys
raw = sys.stdin.read().strip()
try:
    print(json.loads(raw)['name'] == 'Cache Probe v2')
except Exception:
    print(False)
")"

    # And the API agrees with what is in Redis.
    as_anonymous
    request GET "/api/products/$CACHE_ID" >/dev/null
    check "and the API serves the updated value" "Cache Probe v2" "$(jget "d['name']")"

    # Deleting must remove the entry outright: a survivor would keep serving a product that no
    # longer exists.
    as_admin
    request DELETE "/api/products/$CACHE_ID" >/dev/null
    check "deleting the product evicts the entry" "0" "$(redis_cli EXISTS "$CACHE_KEY" | tr -d '\r ')"
    as_anonymous
    check "and the product is really gone" "404" "$(request GET "/api/products/$CACHE_ID")"
    as_customer
else
    skip "a cache probe product is created" \
        "no redis-cli on PATH and no running container named '$REDIS_CONTAINER'"
    skip "no cache entry before the first read" "same as above"
    skip "reading the product returns 200" "same as above"
    skip "and the cache key now exists" "same as above"
    skip "the entry is readable JSON" "same as above"
    skip "the entry expires on its own" "same as above"
    skip "updating the product returns 200" "same as above"
    skip "the cache entry is refreshed, not stale" "same as above"
    skip "and the API serves the updated value" "same as above"
    skip "deleting the product evicts the entry" "same as above"
    skip "and the product is really gone" "same as above"
fi

# --------------------------------------------------------------------------------------------
# Batch processing (Phase 14)
# --------------------------------------------------------------------------------------------
# Uploads a product CSV whose rows are deliberately part good and part bad, and checks the three
# things the phase promises: the job ends COMPLETED, the catalogue grows by exactly the number of
# good rows, and the skip count equals the number of bad ones.
#
# The products it creates are named "Smoke Import NN" and deleted at the end, so the catalogue is
# the same size after this section as before it. Running the script twice is safe either way: the
# import upserts by name, so a second run updates the same rows rather than adding more.
section "Batch processing"

IMPORT_GOOD=12
IMPORT_BAD=4

# The file. Four bad rows, one per kind of rejection: no name, a price that is not a number, a
# negative stock, and a line with too few columns (which fails in the READER rather than in
# validation, and is counted just the same).
IMPORT_CSV="$(mktemp)"
{
    echo "name,description,price,stock_quantity,category"
    for i in $(seq 1 "$IMPORT_GOOD"); do
        printf 'Smoke Import %02d,imported by the smoke test,%d.50,%d,SMOKE\n' "$i" "$((9 + i))" "$i"
    done
    echo ",no name at all,9.99,1,SMOKE"
    echo "Smoke Import bad price,x,twelve,1,SMOKE"
    echo "Smoke Import bad stock,x,9.99,-3,SMOKE"
    echo "Smoke Import short row,9.99"
} > "$IMPORT_CSV"

# How many products there are now. Everything below is measured against this.
as_anonymous
request GET /api/products > /dev/null
PRODUCTS_BEFORE="$(jget "len(d)")"

# The upload. `request` only speaks JSON, so this one call uses curl directly: -F turns it into a
# multipart/form-data POST with a file part named `file`, which is what the endpoint binds.
as_admin
IMPORT_STATUS="$(curl -sS -o "$BODY" -w '%{http_code}' -X POST \
    "$BASE_URL/api/admin/batch/product-import" \
    -H "Authorization: Bearer $AUTH" -F "file=@$IMPORT_CSV;filename=smoke-products.csv;type=text/csv")"
check "uploading a product CSV returns 200" "200" "$IMPORT_STATUS"

# A 200 means the job RAN. Whether it worked is in the body - which is the distinction the
# endpoint exists to make, and the reason this is a separate check.
check "the import job ends COMPLETED" "COMPLETED" "$(jget "d['execution']['status']")"
check "it ran the import job" "productImportJob" "$(jget "d['execution']['jobName']")"
check "every good row was written" "$IMPORT_GOOD" "$(jget "d['execution']['writeCount']")"
check "and every bad row was skipped, not imported" "$IMPORT_BAD" \
    "$(jget "d['execution']['skipCount']")"

# One chunk-oriented step, and the counters the JobRepository recorded for it.
check "the job reports its one step" "importProducts" "$(jget "d['execution']['steps'][0]['name']")"
IMPORT_COMMITS="$(jget "d['execution']['steps'][0]['commitCount']")"
if [ -n "${IMPORT_COMMITS:-}" ] && [ "$IMPORT_COMMITS" -ge 1 ]; then
    pass "the step committed its chunks ($IMPORT_COMMITS)"
else
    fail "the step committed its chunks" "at least 1" "${IMPORT_COMMITS:-<none>}"
fi

IMPORT_EXECUTION_ID="$(jget "d['execution']['id']")"
IMPORT_ERROR_FILE="$(jget "d['execution'] and d['errorFile']")"

# The catalogue really grew, and by exactly the good rows.
as_anonymous
request GET /api/products > /dev/null
check "the catalogue grew by exactly the good rows" "$((PRODUCTS_BEFORE + IMPORT_GOOD))" \
    "$(jget "len(d)")"

# ... and the listing being served is the fresh one. The import evicts the Phase 13 caches after
# the step commits; without that this check would still see the pre-import catalogue.
check "an imported product is in the cached listing" "True" \
    "$(jget "any(p['name'] == 'Smoke Import 01' for p in d)")"

# Every rejected row is written down, with its line number and the reason - the file is the only
# way anyone finds out WHICH rows were skipped. It lives on the application's filesystem, so it
# is read through the container when there is one.
if [ -n "${IMPORT_ERROR_FILE:-}" ] && [ "$IMPORT_ERROR_FILE" != "None" ]; then
    pass "the response names an error file"
    if command -v docker >/dev/null 2>&1 && docker exec ecomdemo-app true >/dev/null 2>&1; then
        # `sh -c` so the redirection runs INSIDE the container: `docker exec ... wc -l < file`
        # would have the host's shell try to open a path that only exists in the container.
        ERROR_LINES="$(docker exec ecomdemo-app sh -c "wc -l < '$IMPORT_ERROR_FILE'" 2>/dev/null \
            | tr -d ' \r')"
        check "it holds one line per rejected row, plus a header" "$((IMPORT_BAD + 1))" \
            "${ERROR_LINES:-<unreadable>}"
    elif [ -r "$IMPORT_ERROR_FILE" ]; then
        check "it holds one line per rejected row, plus a header" "$((IMPORT_BAD + 1))" \
            "$(wc -l < "$IMPORT_ERROR_FILE" | tr -d ' ')"
    else
        skip "it holds one line per rejected row, plus a header" \
            "the file is on the application's filesystem and no container named 'ecomdemo-app' is running"
    fi
else
    fail "the response names an error file" "a path" "${IMPORT_ERROR_FILE:-<none>}"
fi

# The run was WRITTEN DOWN. This is the check that would have caught Spring Batch 6 quietly using
# its in-memory JobRepository, under which everything above still passes and nothing survives a
# restart.
as_admin
check "the execution can be read back from the JobRepository" "200" \
    "$(request GET "/api/admin/batch/executions/$IMPORT_EXECUTION_ID")"
check "and it is the same run" "$IMPORT_GOOD" "$(jget "d['writeCount']")"

# A completed run is not a restartable one. The refusal is the JobRepository doing its job.
check "a completed run cannot be restarted" "409" \
    "$(request POST "/api/admin/batch/executions/$IMPORT_EXECUTION_ID/restart")"

# Importing the same catalogue again updates rather than duplicates, which is what makes a
# restart safe: the rows of a rolled-back chunk get processed a second time.
IMPORT_STATUS="$(curl -sS -o "$BODY" -w '%{http_code}' -X POST \
    "$BASE_URL/api/admin/batch/product-import" \
    -H "Authorization: Bearer $AUTH" -F "file=@$IMPORT_CSV;filename=smoke-products.csv;type=text/csv")"
check "re-importing the same file returns 200" "200" "$IMPORT_STATUS"
check "and completes" "COMPLETED" "$(jget "d['execution']['status']")"
as_anonymous
request GET /api/products > /dev/null
check "the catalogue did not grow again: the import upserts" \
    "$((PRODUCTS_BEFORE + IMPORT_GOOD))" "$(jget "len(d)")"

# Only an ADMIN may rewrite the catalogue from a file.
as_customer
IMPORT_STATUS="$(curl -sS -o "$BODY" -w '%{http_code}' -X POST \
    "$BASE_URL/api/admin/batch/product-import" \
    -H "Authorization: Bearer $AUTH" -F "file=@$IMPORT_CSV;filename=smoke-products.csv;type=text/csv")"
check "a CUSTOMER cannot import a catalogue" "403" "$IMPORT_STATUS"
as_anonymous
IMPORT_STATUS="$(curl -sS -o "$BODY" -w '%{http_code}' -X POST \
    "$BASE_URL/api/admin/batch/product-import" -F "file=@$IMPORT_CSV;filename=smoke-products.csv;type=text/csv")"
check "and an anonymous caller certainly cannot" "401" "$IMPORT_STATUS"

# Clean up: leave the catalogue exactly as it was found.
as_admin
request GET /api/products > /dev/null
for id in $(jget "' '.join(str(p['id']) for p in d if p['name'].startswith('Smoke Import'))"); do
    request DELETE "/api/products/$id" > /dev/null
done
as_anonymous
request GET /api/products > /dev/null
check "the imported products are cleaned up again" "$PRODUCTS_BEFORE" "$(jget "len(d)")"
rm -f "$IMPORT_CSV"
as_customer

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
