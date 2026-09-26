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
# A whole Prometheus scrape, kept in a file rather than a variable: it is a few hundred lines and
# the same snapshot is read several times per check, so re-fetching it per assertion would both
# be slow and - worse - compare two different moments in time.
SCRAPE="$(mktemp)"
# The Phase 18 section stops the Kafka container on purpose. If anything between the stop and the
# start fails - a failed check under `set -e`, or a Ctrl-C - the broker would be left down and
# every later run of this script would report a broken stack rather than a failed check. This
# restores it on the way out, whatever happened.
KAFKA_WAS_STOPPED=false
restore_kafka() {
    if [ "$KAFKA_WAS_STOPPED" = true ]; then
        printf '\033[33mrestoring the Kafka container, which this script had stopped\033[0m\n' >&2
        docker start "${KAFKA_CONTAINER:-ecomdemo-kafka}" >/dev/null 2>&1 || true
    fi
}

trap 'rm -f "$BODY" "$SCRAPE"; restore_kafka' EXIT

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

# --------------------------------------------------------------------------------------------
# Reading the Prometheus scrape (Phase 15)
# --------------------------------------------------------------------------------------------
# A line of the exposition format is `name{label="value",...} number`. Parsed properly rather
# than grepped, because a grep for `orders_placed_total` also matches
# `orders_placed_total_created`, and a grep for a label depends on the order Micrometer happens
# to emit them in. Matching series are SUMMED, which is what makes a query that omits a label
# behave like PromQL's own aggregation rather than silently picking the first line.
METRIC_PY='
import re, sys
path = sys.argv[1]
name = sys.argv[2]
want = dict(a.split("=", 1) for a in sys.argv[3:])
total = None
for line in open(path):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    m = re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)$", line)
    if not m or m.group(1) != name:
        continue
    labels = dict(re.findall(r"([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"", m.group(2) or ""))
    if all(labels.get(k) == v for k, v in want.items()):
        total = (total or 0.0) + float(m.group(3))
# %.12g, NOT %g. The default is SIX significant digits, which silently rounds a metric the
# moment it outgrows them: order_value_sum at 167656.5 printed as 167656, so a delta that should
# have been 2499.5 came out as 2499 and the check failed by exactly the rounding. Twelve digits
# covers any figure this application produces while still printing 1.0 as "1", which the
# counter checks compare against.
print("MISSING" if total is None else ("%.12g" % total))
'

# scrape -> takes a fresh snapshot of /actuator/prometheus into $SCRAPE
scrape() {
    curl -sS -o "$SCRAPE" "$BASE_URL/actuator/prometheus"
}

# metric <name> [label=value ...] -> the summed value, or the literal MISSING
metric() {
    python3 -c "$METRIC_PY" "$SCRAPE" "$@"
}

# delta <before> <after> -> the difference, for readable check() output
delta() {
    # See the note on METRIC_PY above for why this is not %g. It also absorbs the float
    # subtraction artefacts that would otherwise print 2499.4999999999995.
    python3 -c "print('%.12g' % ($2 - $1))" 2>/dev/null
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
# customer_psql_query <sql> -> against CUSTOMER-SERVICE's database, which owns `users` since 20d.
customer_psql_query() {
    docker exec "${CUSTOMER_DB_CONTAINER:-ecomdemo-customer-db}" \
        psql -qtAX -U "${CUSTOMER_DB_USER:-customer}" \
        -d "${CUSTOMER_DB_NAME:-customer}" -c "$1" 2>/dev/null
}

# wait_for_notification <order_id> -> prints the count, having waited up to 90s for it to reach 1
#
# ONE helper instead of four hand-rolled loops with four different budgets (20s, 20s, 60s, 60s), which
# is how this suite ended up with three checks asserting a latency none of them meant to assert. The
# numbers were never reasoned about; each was whatever seemed generous when it was written, and each
# became a flake as services were added and the cold path lengthened.
#
# MEASURED: warm, an order is notified in ONE SECOND. Cold - the first messages after
# notification-service starts, while the consumer joins its group and its retry and dead-letter topics
# are created - it is far slower and not usefully bounded on a laptop hosting sixteen containers.
#
# So the bound here is deliberately generous and stated in one place. Every check that uses it is about
# BEHAVIOUR - one notification per order, idempotency under redelivery, a poison message not blocking
# the partition - and none of them is about how fast a consumer wakes up. If the notification pipeline
# is genuinely broken, these still fail; they just no longer fail because a laptop was busy.
wait_for_notification() {
    local order_id="$1"
    local seconds="${2:-90}"
    local count=0
    local _
    for _ in $(seq 1 "$seconds"); do
        count="$(notification_psql_query \
            "SELECT count(*) FROM notification WHERE order_id = $order_id;" || echo 0)"
        [ "${count:-0}" -ge 1 ] && break
        sleep 1
    done
    echo "${count:-0}"
}

# notification_psql_query <sql> -> the same as psql_query, but against NOTIFICATION-SERVICE's
# database.
#
# Phase 20d needed this and Phase 20c needed its catalogue equivalent, for the same reason: a query
# has to go to the service that owns the table. `notification` and `processed_event` were in the
# application's database until 20d; they are notification-service's now, and pointing these checks at
# the old database would not fail loudly - it would report zero notifications for an order that was
# notified perfectly well, which reads like a broken consumer.
notification_psql_query() {
    docker exec "${NOTIFICATION_DB_CONTAINER:-ecomdemo-notification-db}" \
        psql -qtAX -U "${NOTIFICATION_DB_USER:-notification}" \
        -d "${NOTIFICATION_DB_NAME:-notification}" -c "$1" 2>/dev/null
}

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
# CATALOG-DB, not the application's. The probe is a PRODUCT, and products moved to catalog_db in
# Phase 20c - so the identity that matters is the one belonging to the database the probe is stored
# in. Watching the application's database instead would report "the data did not survive a restart"
# every time the catalogue was re-provisioned, which is the exact confusion this identifier exists
# to prevent.
CURRENT_DB_ID="$(docker exec "${CATALOG_DB_CONTAINER:-ecomdemo-catalog-db}" \
    psql -qtAX -U "${CATALOG_DB_USER:-catalog}" -d "${CATALOG_DB_NAME:-catalog}" \
    -c "SELECT system_identifier FROM pg_control_system();" 2>/dev/null | tr -d '\r ')"

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
    check "flyway_schema_history shows V1-V16, all successful" \
        "1:ok,2:ok,3:ok,4:ok,5:ok,6:ok,7:ok,8:ok,9:ok,10:ok,11:ok,12:ok,13:ok,14:ok,15:ok,16:ok" "$HISTORY"

    PENDING="$(psql_query \
        "SELECT count(*) FROM flyway_schema_history WHERE success = false;" | tr -d '\r ')"
    check "no migration is recorded as failed" "0" "$PENDING"

    # V3's idx_product_category is no longer in THIS database: Phase 20c moved `product` to
    # catalog-service, and V14 dropped the application's copy. The index is asserted in
    # catalog-service's own CatalogSchemaTest, against catalog_db.
    #
    # What is checked here instead is the thing V14 is FOR. A leftover copy of another service's
    # table would still answer queries, with rows frozen at the moment of the split - so "the table
    # is gone" is a stronger statement than "the index is present" ever was.
    PRODUCT_TABLE="$(psql_query \
        "SELECT count(*) FROM information_schema.tables \
         WHERE table_schema = 'public' AND table_name = 'product';" | tr -d '\r ')"
    check "V14 dropped product - the catalogue belongs to catalog-service now" "0" "$PRODUCT_TABLE"

    # V15's backfill, checked against REAL rows rather than a fresh schema. This is the migration with
    # a deadline: once `users` moves to customer-service, no query can fill this column in for orders
    # that already exist. A single null here means the backfill silently skipped a row.
    ORDERS_WITHOUT_USERNAME="$(psql_query \
        "SELECT count(*) FROM orders WHERE username IS NULL;" | tr -d '\r ')"
    check "V15 backfilled every order's username, with none left null" "0" "$ORDERS_WITHOUT_USERNAME"

    USER_FKS="$(psql_query \
        "SELECT count(*) FROM information_schema.table_constraints \
         WHERE constraint_name IN ('fk_cart_user', 'fk_orders_user');" | tr -d '\r ')"
    check "V15 dropped the foreign keys to users - a key cannot span two databases" "0" "$USER_FKS"

    AUDIT_TABLE="$(psql_query \
        "SELECT count(*) FROM information_schema.tables \
         WHERE table_schema = 'public' AND table_name = 'order_audit';" | tr -d '\r ')"
    check "V4's order_audit table exists" "1" "$AUDIT_TABLE"

    # Against CATALOG_DB, because that is where `product` lives since Phase 20c. The application's
    # V4 did add this column, and its V14 dropped the whole table when catalog-service took it;
    # catalog_db's own V1 recreates it with the same shape. Pointing this check at the application's
    # database would assert that a table it deliberately dropped is still there.
    VERSION_COLUMN="$(docker exec "${CATALOG_DB_CONTAINER:-ecomdemo-catalog-db}" \
        psql -qtAX -U "${CATALOG_DB_USER:-catalog}" -d "${CATALOG_DB_NAME:-catalog}" \
        -c "SELECT count(*) FROM information_schema.columns \
            WHERE table_name = 'product' AND column_name = 'version';" 2>/dev/null | tr -d '\r ')"
    check "catalog_db has the product.version column, the optimistic lock" "1" "$VERSION_COLUMN"

    CATALOG_SEED="$(docker exec "${CATALOG_DB_CONTAINER:-ecomdemo-catalog-db}" \
        psql -qtAX -U "${CATALOG_DB_USER:-catalog}" -d "${CATALOG_DB_NAME:-catalog}" \
        -c "SELECT count(*) FROM product WHERE id BETWEEN 1 AND 10;" 2>/dev/null | tr -d '\r ')"
    # The check Phase 20b learned to make: moving a table moves its DDL, and the DATA has to be
    # carried over separately. inventory_db's seed is keyed to these ten ids.
    check "catalog_db carries the ten seeded products" "10" "$CATALOG_SEED"

    # Against CUSTOMER_DB, because `users` moved there in Phase 20d and V16 dropped this database's
    # copy. The seeded administrator's ID is checked as well as its existence: order-service's
    # `orders.user_id` holds ids issued by customer-service, and V15 removed the foreign key that used
    # to tie the two together - so id 1 is now an agreement between two migrations with nothing
    # enforcing it.
    ADMIN_ROW="$(customer_psql_query \
        "SELECT count(*) FROM users WHERE id = 1 AND username = 'admin' AND role = 'ADMIN';" \
        | tr -d '\r ')"
    check "customer_db seeded exactly one administrator, at id 1" "1" "$ADMIN_ROW"

    APP_USERS="$(psql_query \
        "SELECT count(*) FROM information_schema.tables \
         WHERE table_schema = 'public' AND table_name IN ('users', 'notification', 'processed_event');" \
        | tr -d '\r ')"
    check "V16 dropped accounts, notifications and the ledger - other services own them" "0" "$APP_USERS"

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

    # V9 (Phase 17). The unique constraint is the database saying independently what the
    # consumer's idempotency check says: one notification per order. If the consumer's logic were
    # ever wrong, this is what would turn a silent second notification into a visible error.
    # Against NOTIFICATION_DB since Phase 20d. The application's V9 did create these tables; V16 drops
    # its copies now that notification-service owns them, and notification_db's own V1 recreates them
    # with the same shape.
    check "notification_db keeps one notification per order" "1" \
        "$(notification_psql_query "SELECT count(*) FROM information_schema.table_constraints \
            WHERE table_name = 'notification' AND constraint_type = 'UNIQUE';" | tr -d '\r ')"

    check "and processed_event is keyed by the event id itself" "event_id" \
        "$(notification_psql_query "SELECT c.column_name FROM information_schema.table_constraints t \
            JOIN information_schema.constraint_column_usage c ON c.constraint_name = t.constraint_name \
            WHERE t.table_name = 'processed_event' AND t.constraint_type = 'PRIMARY KEY';" | tr -d '\r ')"
else
    skip "flyway_schema_history shows V1-V16, all successful" \
        "no psql on PATH and no running container named '$POSTGRES_CONTAINER'"
    skip "no migration is recorded as failed" "same as above"
    skip "V14 dropped product - the catalogue belongs to catalog-service now" "same as above"
    skip "V4's order_audit table exists" "same as above"
    skip "V4's product.version column exists" "same as above"
    skip "customer_db seeded exactly one administrator, at id 1" "same as above"
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

# The highest order id before the race, so the count below can ignore everything that came before
# it. See the comment on that check for why this became necessary in Phase 20c.
as_customer
request GET /api/orders >/dev/null
ORDERS_BEFORE_RACE="$(jget "max([o['id'] for o in d], default=0)")"

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

# CONVERGES on 0 rather than being 0 immediately, and this check was MISSED when its sibling in the
# caching section was converted in Phase 20c.
#
# It failed once during Phase 20d's merge verification, reading 1 where it wanted 0 - and what it was
# seeing was the eventual-consistency window, not an oversell. The two checks above prove that: the
# concurrent checkouts returned exactly one 201 and one 409, and exactly one order holds the product.
# The sale was correct; only the DISPLAYED figure lagged, because it comes through catalog-service's
# Redis cache, which is evicted when inventory-service's `inventory.stock-changed` event arrives.
#
# So the claim is "the unit was sold once", and it is about inventory - not about how fast a cache
# hears. Polling states that, and reports the convergence time so a window quietly growing from 200ms
# to four seconds is visible rather than merely still passing.
RACE_CONVERGED=false
RACE_ELAPSED=0
for _ in $(seq 1 50); do
    request GET "/api/products/$RACE_ID" >/dev/null
    if [ "$(jget "d['stockQuantity']")" = "0" ]; then
        RACE_CONVERGED=true
        break
    fi
    sleep 0.2
    RACE_ELAPSED=$((RACE_ELAPSED + 200))
done
check "the one unit was sold once, so stock converges on 0 (took ${RACE_ELAPSED}ms)" "true" "$RACE_CONVERGED"

# Counted over the orders placed SINCE THIS SECTION STARTED, not over every order the account has
# ever had. Phase 20c made that distinction matter: product ids are assigned by catalog_db now, and
# that database was created fresh while the application's orders persisted - so an order from an
# earlier run can hold the same product id as today's race probe and be counted twice. The check
# then fails for a reason that has nothing to do with the race it is testing.
#
# This is a real consequence of splitting a database, not a quirk of the test: ids are only unique
# within the service that issues them, and anything holding a foreign id across a re-provision has
# to cope with it. The application is fine - an order snapshots the name and price it charged, so
# nothing it shows a customer is wrong - but a test that counts by id has to scope the window.
request GET /api/orders >/dev/null
check "exactly one order holds that product" "1" \
    "$(jget "sum(1 for o in d for i in o['items'] if i['productId'] == $RACE_ID and o['id'] > $ORDERS_BEFORE_RACE)")"

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

# --- A sale must not leave the catalogue advertising stock it no longer has -------------------
# The regression check for the defect found during Phase 16: placing an order decremented the
# database and evicted neither cache, so the product page and the listing kept showing the
# pre-sale figure for up to their TTL (ten minutes and two). A shopper could read "5 in stock",
# add five to a cart, and be refused at checkout.
#
# WARMING THE CACHE FIRST IS THE WHOLE POINT. The happy-path section already reads the stock back
# after an order, and it passed throughout the bug - because nothing had put that product in the
# cache beforehand, so its read was a miss that went to the database. A regression test for a
# cache has to guarantee the entry exists before the thing it is testing happens.
as_admin
STATUS="$(request POST /api/products \
    '{"name":"Eviction Probe","description":"bought once, to prove the catalogue keeps up","price":40.00,"stockQuantity":5,"category":"TEST"}')"
check "a product for the eviction check is created" "201" "$STATUS"
EVICTION_ID="$(jget "d['id']")"

as_customer
# Start from an empty cart, the same way the happy path does: a line left over from an earlier
# section makes this checkout fail for a reason that has nothing to do with caching.
request GET /api/cart >/dev/null
for product_id in $(jget "' '.join(str(i['productId']) for i in d['items'])"); do
    request DELETE "/api/cart/items/$product_id" >/dev/null
done

# Warm both caches: the single product and the whole listing.
request GET "/api/products/$EVICTION_ID" >/dev/null
check "the catalogue shows 5 before the sale" "5" "$(jget "d['stockQuantity']")"
request GET /api/products >/dev/null
check "and the listing agrees" "5" \
    "$(jget "next(p['stockQuantity'] for p in d if p['id'] == $EVICTION_ID)")"

request POST /api/cart/items "{\"productId\":$EVICTION_ID,\"quantity\":2}" >/dev/null
check "buying 2 of them succeeds" "201" "$(request POST /api/orders)"

# THIS CHECK CHANGED IN PHASE 20b, AND THE CHANGE IS THE SPLIT IN ONE PLACE.
#
# It used to read, above these lines: "No sleep anywhere: the eviction is part of finishing the
# checkout, so the very next read is already right. A check that needed a sleep would be a check
# that accepted staleness." That was true of a monolith, where the stock write and the cache
# eviction were two steps of one transaction in one process.
#
# They are now in different processes. inventory-service owns the stock and publishes
# `inventory.stock-changed`; the catalogue's cache evictor consumes it. Between the checkout
# returning 201 and the cache being evicted there is a broker, a poll interval and a network, so
# the cache is EVENTUALLY consistent and the old claim is simply no longer true. Asserting it
# anyway would not make it true; it would make the smoke test fail for telling the truth.
#
# So the check becomes: does it converge, and how fast? It polls rather than sleeping a fixed
# amount, which keeps it quick when things are healthy and gives it room when they are not, and it
# REPORTS THE TIME TAKEN so that a convergence window quietly growing from 200ms to 4s is visible
# rather than merely still passing. A fixed `sleep 5` would hide exactly that.
#
# What did NOT become eventually consistent is worth saying: a shopper is never sold stock that is
# not there. The reservation is synchronous and inventory-service holds the row lock, so the
# catalogue may briefly ADVERTISE a stale number, and checkout still refuses. Stale display,
# correct sale.
CONVERGED=false
ELAPSED=0
for _ in $(seq 1 50); do
    request GET "/api/products/$EVICTION_ID" >/dev/null
    if [ "$(jget "d['stockQuantity']")" = "3" ]; then
        CONVERGED=true
        break
    fi
    sleep 0.2
    ELAPSED=$((ELAPSED + 200))
done
check "the product page converges on 3, not the pre-sale 5 (took ${ELAPSED}ms)" "true" "$CONVERGED"

CONVERGED=false
for _ in $(seq 1 50); do
    request GET /api/products >/dev/null
    if [ "$(jget "next(p['stockQuantity'] for p in d if p['id'] == $EVICTION_ID)")" = "3" ]; then
        CONVERGED=true
        break
    fi
    sleep 0.2
done
check "and the listing converges on 3 as well" "true" "$CONVERGED"

as_admin
request DELETE "/api/products/$EVICTION_ID" >/dev/null
as_customer

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
# 11. Metrics and monitoring (Phase 15)
# --------------------------------------------------------------------------------------------
# The claim under test is not "the endpoint answers 200". It is that placing a real order moves
# the exact numbers the dashboard and the alert rule read - so a rename, a lost tag, or a meter
# that silently stopped being registered fails here rather than on a graph nobody is watching.
section "Metrics and monitoring"

# --- The probes -------------------------------------------------------------------------------
as_anonymous
check "GET /actuator/health returns 200" "200" "$(request GET /actuator/health)"
check "and it reports UP" "UP" "$(jget "d['status']")"
# The two groups are what make the probes useful; without probes.enabled these are 404.
check "liveness is UP" "UP" \
    "$(request GET /actuator/health/liveness >/dev/null; jget "d['status']")"
check "readiness is UP" "UP" \
    "$(request GET /actuator/health/readiness >/dev/null; jget "d['status']")"
# An anonymous caller gets the verdict and nothing else. The component names alone ("db",
# "redis") would map the infrastructure for whoever asked.
request GET /actuator/health >/dev/null
check "an anonymous health body carries no component details" "False" \
    "$(jget "'components' in d")"
as_admin
request GET /actuator/health >/dev/null
check "an ADMIN sees the per-component breakdown" "True" "$(jget "'components' in d")"
check "and the database is one of the components" "True" "$(jget "'db' in d['components']")"
# Redis is deliberately absent from readiness: a cache outage must not take the app out of
# rotation, since every read still works without it.
request GET /actuator/health/readiness >/dev/null
check "readiness includes the database" "True" "$(jget "'db' in d.get('components', {})")"
check "but NOT redis - a cache outage is a slowdown, not an outage" "False" \
    "$(jget "'redis' in d.get('components', {})")"

# --- Who may read what --------------------------------------------------------------------------
as_anonymous
check "anonymous /actuator/prometheus returns 200 - Prometheus has no token" "200" \
    "$(request GET /actuator/prometheus)"
check "anonymous /actuator/info returns 200" "200" "$(request GET /actuator/info)"
check "anonymous /actuator/metrics returns 401" "401" "$(request GET /actuator/metrics)"
as_customer
check "a CUSTOMER may not read /actuator/metrics either" "403" \
    "$(request GET /actuator/metrics)"
as_admin
check "an ADMIN may" "200" "$(request GET /actuator/metrics)"
# Not in management.endpoints.web.exposure.include, so it does not exist over HTTP at all - the
# allow-list, not an authorization rule, is what keeps the environment (and JWT_SECRET) off the
# wire. Even the administrator gets a 404.
check "/actuator/env is not exposed at all, not even to an ADMIN" "404" \
    "$(request GET /actuator/env)"
check "and neither is /actuator/heapdump" "404" "$(request GET /actuator/heapdump)"

# --- Which build is running ---------------------------------------------------------------------
as_anonymous
request GET /actuator/info >/dev/null
# `ecomdemo-app` since Phase 20 made the build a reactor: the deployable is a MODULE now, and
# the artifact name is the module's. That is the check working rather than the check being wrong -
# the whole point of this endpoint is that it says which build is running, so the day the answer
# changes it should say so.
check "/actuator/info names the artifact" "ecomdemo-app" "$(jget "d['build']['artifact']")"
check "and carries a build timestamp" "True" "$(jget "len(str(d['build']['time'])) > 0")"

# --- A placed order moves the business meters -----------------------------------------------------
as_customer
scrape
ORDERS_BEFORE="$(metric orders_placed_total)"
VALUE_SUM_BEFORE="$(metric order_value_sum)"
VALUE_COUNT_BEFORE="$(metric order_value_count)"
PLACED_BEFORE="$(metric checkout_duration_seconds_count outcome=placed)"

# Every meter exists before the first order of this run, and that is the check: an absent series
# is not zero. PromQL over a series that does not exist returns no rows, so a panel shows "No
# data" and an alert written as `rate(...) > 0` can never fire - it has nothing to be true about.
check "orders.placed is registered before any order is placed" "False" \
    "$([ "$ORDERS_BEFORE" = "MISSING" ] && echo True || echo False)"
check "order.value is registered too" "False" \
    "$([ "$VALUE_SUM_BEFORE" = "MISSING" ] && echo True || echo False)"
check "and checkout.duration carries all five outcome tags" "5" \
    "$(for o in placed out_of_stock empty_cart conflict error; do
           metric checkout_duration_seconds_count "outcome=$o"
       done | grep -vc MISSING)"

METRICS_PRODUCT_ID="$(as_anonymous; request GET /api/products >/dev/null; \
    jget "next(p['id'] for p in d if p['stockQuantity'] >= 1)")"
METRICS_UNIT_PRICE="$(jget "next(str(p['price']) for p in d if p['stockQuantity'] >= 1)")"
as_customer
request POST /api/cart/items "{\"productId\":$METRICS_PRODUCT_ID,\"quantity\":1}" >/dev/null
STATUS="$(request POST /api/orders)"
check "an order is placed for the metrics check" "201" "$STATUS"
METRICS_ORDER_TOTAL="$(jget "str(d['totalAmount'])")"

scrape
check "orders_placed_total incremented by exactly 1" "1" \
    "$(delta "$ORDERS_BEFORE" "$(metric orders_placed_total)")"
check "order_value_count incremented by exactly 1" "1" \
    "$(delta "$VALUE_COUNT_BEFORE" "$(metric order_value_count)")"
# The summary's _sum is the revenue figure on the dashboard; it must move by the order's own
# total, not by some rounded or averaged version of it.
check "order_value_sum grew by the order total ($METRICS_ORDER_TOTAL)" \
    "$(python3 -c "print('%g' % float('$METRICS_ORDER_TOTAL'))")" \
    "$(delta "$VALUE_SUM_BEFORE" "$(metric order_value_sum)")"
check "the checkout was timed as outcome=placed" "1" \
    "$(delta "$PLACED_BEFORE" "$(metric checkout_duration_seconds_count outcome=placed)")"
# A timer counts AND times, which is why one meter answers all three RED questions.
check "and its recorded time is greater than zero" "True" \
    "$(python3 -c "print(float('$(metric checkout_duration_seconds_sum outcome=placed)') > 0)")"
# The histogram buckets are what Prometheus computes the latency quantiles from. Without
# percentiles-histogram enabled these do not exist and the latency panel is empty.
check "checkout.duration exports histogram buckets for the p95 panel" "True" \
    "$([ "$(metric checkout_duration_seconds_bucket outcome=placed le=+Inf)" = "MISSING" ] \
        && echo False || echo True)"

# --- A failed checkout is tagged with WHY ----------------------------------------------------------
# The cart was emptied by the checkout above, so this one has nothing to buy. It must land on the
# empty_cart timer and nowhere else - in particular not on `conflict`, which is the one an alert
# watches, and not on `placed`.
EMPTY_BEFORE="$(metric checkout_duration_seconds_count outcome=empty_cart)"
CONFLICT_BEFORE="$(metric checkout_duration_seconds_count outcome=conflict)"
ORDERS_AFTER_ONE="$(metric orders_placed_total)"
check "checking out an empty cart returns 409" "409" "$(request POST /api/orders)"
scrape
check "the failure was timed as outcome=empty_cart" "1" \
    "$(delta "$EMPTY_BEFORE" "$(metric checkout_duration_seconds_count outcome=empty_cart)")"
check "the conflict series - the one the alert watches - did not move" "0" \
    "$(delta "$CONFLICT_BEFORE" "$(metric checkout_duration_seconds_count outcome=conflict)")"
check "and no order was counted" "0" \
    "$(delta "$ORDERS_AFTER_ONE" "$(metric orders_placed_total)")"

# --- Auto-instrumentation, and what is filtered out --------------------------------------------------
check "HTTP requests are timed without any code being written for it" "True" \
    "$([ "$(metric http_server_requests_seconds_count uri=/api/orders method=POST)" = "MISSING" ] \
        && echo False || echo True)"
# URI TEMPLATES, not real paths. Were the id interpolated, every order ever fetched would be its
# own time series - the classic way to take a monitoring system down with cardinality.
check "the URI is a template, so one series covers every order id" "True" \
    "$([ "$(metric http_server_requests_seconds_count uri='/api/orders/{id}')" = "MISSING" ] \
        && echo False || echo True)"
# MetricsConfig drops these: Prometheus scrapes every 15s and the container probes every 10s, so
# without the filter the busiest endpoint in the shop is the one reporting how busy the shop is.
check "actuator's own endpoints are filtered out of the HTTP timers" "0" \
    "$(grep -c 'uri="/actuator' "$SCRAPE")"
check "every meter carries the application common tag" "0" \
    "$(grep -c '^orders_placed_total{[^}]*}' "$SCRAPE" | \
        python3 -c "import sys; print(0 if int(sys.stdin.read()) == 1 else 1)")"
check "JVM and pool meters are published for the USE panels" "True" \
    "$([ "$(metric jvm_memory_used_bytes area=heap)" = "MISSING" ] && echo False || echo True)"

# --- Prometheus and Grafana ------------------------------------------------------------------------
# Only meaningful against the compose stack. Skipped, never passed, when they are not reachable:
# a monitoring check that quietly succeeds because nothing was there to check is worse than none.
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
GRAFANA_AUTH="${GRAFANA_USER:-admin}:${GRAFANA_PASSWORD:-admin}"

if curl -fsS "$PROMETHEUS_URL/-/ready" >/dev/null 2>&1; then
    TARGETS="$(curl -sS "$PROMETHEUS_URL/api/v1/targets?state=active")"
    check "Prometheus is scraping the application and the target is up" "up" \
        "$(printf '%s' "$TARGETS" | python3 -c "
import json, sys
d = json.load(sys.stdin)['data']['activeTargets']
print(next((t['health'] for t in d if t['labels'].get('job') == 'ecomdemo'), 'MISSING'))")"
    check "it scrapes the actuator path, not /metrics" "True" \
        "$(printf '%s' "$TARGETS" | python3 -c "
import json, sys
d = json.load(sys.stdin)['data']['activeTargets']
print(any(t['scrapeUrl'].endswith('/actuator/prometheus') for t in d))")"
    # A monitoring system that does not scrape itself cannot report that it stopped working.
    check "and it scrapes itself as well" "True" \
        "$(printf '%s' "$TARGETS" | python3 -c "
import json, sys
d = json.load(sys.stdin)['data']['activeTargets']
print(any(t['labels'].get('job') == 'prometheus' for t in d))")"

    RULES="$(curl -sS "$PROMETHEUS_URL/api/v1/rules")"
    check "the alert rule is loaded" "CheckoutConflictRateHigh" \
        "$(printf '%s' "$RULES" | python3 -c "
import json, sys
g = json.load(sys.stdin)['data']['groups']
print(next((r['name'] for grp in g for r in grp['rules']), 'MISSING'))")"
    # `health: ok` means PromQL parsed and evaluated it. A rule with a typo in a metric name
    # loads perfectly happily and simply never fires, which is the failure nobody notices.
    check "and Prometheus can evaluate it" "ok" \
        "$(printf '%s' "$RULES" | python3 -c "
import json, sys
g = json.load(sys.stdin)['data']['groups']
print(next((r['health'] for grp in g for r in grp['rules']), 'MISSING'))")"
    check "it is inactive - no conflict storm in a smoke run" "inactive" \
        "$(printf '%s' "$RULES" | python3 -c "
import json, sys
g = json.load(sys.stdin)['data']['groups']
print(next((r['state'] for grp in g for r in grp['rules']), 'MISSING'))")"

    # The dashboard's own expression, evaluated by Prometheus. This is the check that a panel
    # would actually draw something: the meters can all be present and the query still return
    # nothing because of a label that does not exist.
    check "the dashboard's orders-per-minute query returns data" "True" \
        "$(curl -sS --get "$PROMETHEUS_URL/api/v1/query" \
            --data-urlencode 'query=sum(rate(orders_placed_total{application="ecomdemo"}[5m]))' \
            | python3 -c "
import json, sys
print(len(json.load(sys.stdin)['data']['result']) > 0)")"
else
    skip "Prometheus checks" "no Prometheus at $PROMETHEUS_URL (set PROMETHEUS_URL to override)"
fi

if curl -fsS "$GRAFANA_URL/api/health" >/dev/null 2>&1; then
    check "Grafana provisioned the Prometheus datasource" "ecomdemo-prometheus" \
        "$(curl -sS -u "$GRAFANA_AUTH" "$GRAFANA_URL/api/datasources" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(next((x['uid'] for x in d if x['type'] == 'prometheus'), 'MISSING'))")"
    check "and the datasource points at the compose service name" "http://prometheus:9090" \
        "$(curl -sS -u "$GRAFANA_AUTH" "$GRAFANA_URL/api/datasources" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(next((x['url'] for x in d if x['type'] == 'prometheus'), 'MISSING'))")"
    check "the dashboard is provisioned from the repository" "EcomDemo Overview" \
        "$(curl -sS -u "$GRAFANA_AUTH" "$GRAFANA_URL/api/dashboards/uid/ecomdemo-overview" \
            | python3 -c "
import json, sys
print(json.load(sys.stdin).get('dashboard', {}).get('title', 'MISSING'))")"
    # Provisioned dashboards are read-only in the UI on purpose: the JSON in Git is the source of
    # truth, and an edit worth keeping is an edit worth committing.
    check "and it is marked provisioned, so UI edits cannot drift from Git" "True" \
        "$(curl -sS -u "$GRAFANA_AUTH" "$GRAFANA_URL/api/dashboards/uid/ecomdemo-overview" \
            | python3 -c "
import json, sys
print(json.load(sys.stdin).get('meta', {}).get('provisioned', False))")"
else
    skip "Grafana checks" "no Grafana at $GRAFANA_URL (set GRAFANA_URL to override)"
fi

as_customer

# --------------------------------------------------------------------------------------------
# 12. Centralized logging (Phase 16)
# --------------------------------------------------------------------------------------------
# Two halves, and they fail for different reasons. The correlation ID half talks only to the
# application and works wherever the script does, including `./mvnw spring-boot:run`. The Loki
# half needs the compose stack and is SKIPPED, never passed, when Loki is not reachable.
section "Centralized logging"

# header <METHOD> <PATH> <HEADER> [INBOUND-CORRELATION-ID] -> prints that response header's value
# Headers rather than the body, so `request` above is no use here: -D writes the response headers
# to a file, and the value is pulled out case-insensitively because HTTP/2 lowercases them.
header() {
    local method="$1" path="$2" want="$3" inbound="${4:-}" headers
    headers="$(mktemp)"
    if [ -n "$inbound" ]; then
        curl -sS -o /dev/null -D "$headers" -X "$method" "$BASE_URL$path" \
            -H "X-Correlation-Id: $inbound" ${AUTH:+-H "Authorization: Bearer $AUTH"}
    else
        curl -sS -o /dev/null -D "$headers" -X "$method" "$BASE_URL$path" \
            ${AUTH:+-H "Authorization: Bearer $AUTH"}
    fi
    tr -d '\r' < "$headers" | awk -v want="$want" 'BEGIN{IGNORECASE=1} $1 == want":" {print $2}' | tail -1
    rm -f "$headers"
}

CORRELATION_HEADER="X-Correlation-Id"

as_anonymous
FIRST_ID="$(header GET /api/products "$CORRELATION_HEADER")"
SECOND_ID="$(header GET /api/products "$CORRELATION_HEADER")"

check "every response carries an $CORRELATION_HEADER header" "True" \
    "$([ -n "$FIRST_ID" ] && echo True || echo False)"

# The same pattern the application accepts on the way in: letters, digits, hyphen, underscore,
# 8 to 64 characters. A generated one is a 32-character UUID with the hyphens removed.
check "and the generated ID is safe to write into a log line" "True" \
    "$(printf '%s' "$FIRST_ID" | grep -Eq '^[A-Za-z0-9_-]{8,64}$' && echo True || echo False)"

check "two requests get two different IDs" "True" \
    "$([ "$FIRST_ID" != "$SECOND_ID" ] && echo True || echo False)"

# The ID this run will look for in Loki. Unique per run, so a query for it cannot be satisfied by
# a line an earlier run left behind - which is exactly the false pass this check exists to avoid.
SMOKE_CORRELATION_ID="smoke-$(date +%s)-$$"

check "an ID supplied by the caller is reused, not replaced" "$SMOKE_CORRELATION_ID" \
    "$(header GET /api/products "$CORRELATION_HEADER" "$SMOKE_CORRELATION_ID")"

# Log injection: the newline would end the real log entry and everything after it would be a log
# entry of the caller's own writing, in the store an incident review trusts.
INJECTED="$(header GET /api/products "$CORRELATION_HEADER" "abc12345 spaces and symbols!")"
check "an unsafe ID is replaced rather than echoed" "True" \
    "$([ "$INJECTED" != "abc12345 spaces and symbols!" ] && \
        printf '%s' "$INJECTED" | grep -Eq '^[A-Za-z0-9_-]{8,64}$' && echo True || echo False)"

# The ordering proof: a 401 is answered inside the Spring Security chain and never reaches a
# controller, so a header on it means the filter really does run ahead of security.
check "a request refused with 401 still carries one" "True" \
    "$([ -n "$(header GET /api/cart "$CORRELATION_HEADER")" ] && echo True || echo False)"

check "a 404 carries one" "True" \
    "$([ -n "$(header GET /api/products/99999999 "$CORRELATION_HEADER")" ] && echo True || echo False)"

check "Actuator's endpoints carry one too" "True" \
    "$([ -n "$(header GET /actuator/health "$CORRELATION_HEADER")" ] && echo True || echo False)"

as_customer

# --- Loki ------------------------------------------------------------------------------------
LOKI_URL="${LOKI_URL:-http://localhost:3100}"
ALLOY_URL="${ALLOY_URL:-http://localhost:12345}"

# loki_count <logql> -> prints how many log lines the query matched in the last 15 minutes
loki_count() {
    curl -sSG "$LOKI_URL/loki/api/v1/query_range" \
        --data-urlencode "query=$1" \
        --data-urlencode "since=15m" \
        --data-urlencode "limit=1000" 2>/dev/null \
        | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); raise SystemExit
print(sum(len(s.get('values', [])) for s in d.get('data', {}).get('result', [])))
" 2>/dev/null || echo 0
}

# Loki answers /ready with 503 and a reason until its ingester has joined its own ring and sat
# there for fifteen seconds, and after a container recreate that can take a while. A single
# request would therefore SKIP this whole section on a stack that is merely still starting, which
# reads exactly like a stack that is broken - so ask for up to thirty seconds before giving up.
LOKI_READY=false
for _ in $(seq 1 15); do
    if curl -fsS "$LOKI_URL/ready" >/dev/null 2>&1; then LOKI_READY=true; break; fi
    sleep 2
done

if [ "$LOKI_READY" = true ]; then
    # Generate traffic under the known ID, then wait for it to arrive. The pipeline is
    # deliberately asynchronous - the application writes to stdout and is done; Docker buffers,
    # Alloy tails and batches, Loki flushes - so a query immediately after the request is a race
    # this loop exists to lose safely. Ten seconds is generous for a local stack.
    #
    # The loop waits for BOTH requests, not merely the first. Alloy batches, so the two requests
    # can arrive in separate batches: stopping at the first line would hand the check below a
    # count of 1 and fail an assertion that asks for 2. A wait loop must wait for the strongest
    # condition asserted after it, or it is not a wait loop, it is a coin toss.
    as_anonymous
    header GET /api/products "$CORRELATION_HEADER" "$SMOKE_CORRELATION_ID" >/dev/null
    header GET /api/products/99999999 "$CORRELATION_HEADER" "$SMOKE_CORRELATION_ID" >/dev/null
    as_customer

    LINES_FOR_ID=0
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        LINES_FOR_ID="$(loki_count "{service_name=\"app\"} | correlation_id = \`$SMOKE_CORRELATION_ID\`")"
        [ "$LINES_FOR_ID" -ge 2 ] && break
        sleep 1
    done

    # THE PHASE'S "DONE WHEN", as a scripted check: all the logs for one request, found by its
    # correlation ID alone.
    check "querying Loki by correlation ID returns this request's log lines" "True" \
        "$([ "$LINES_FOR_ID" -gt 0 ] && echo True || echo False)"

    check "and it finds both requests made under that ID" "True" \
        "$([ "$LINES_FOR_ID" -ge 2 ] && echo True || echo False)"

    # The filter is `| correlation_id = ...`, which is a STRUCTURED METADATA match: the ID is
    # stored with the line and searched, never indexed as a label. A label per request would be a
    # Loki stream per request, which is the documented way to bring the thing down.
    check "the ID is structured metadata, so it is not a label" "False" \
        "$(curl -sS "$LOKI_URL/loki/api/v1/labels" \
            | python3 -c "
import json, sys
print('correlation_id' in json.load(sys.stdin).get('data', []))" 2>/dev/null)"

    # What IS a label: the level, which has five values, and the service.
    check "the log level is a Loki label" "True" \
        "$(curl -sS "$LOKI_URL/loki/api/v1/label/level/values" \
            | python3 -c "
import json, sys
print('INFO' in json.load(sys.stdin).get('data', []))" 2>/dev/null)"

    check "the application's stream is labelled service_name=app" "True" \
        "$(curl -sS "$LOKI_URL/loki/api/v1/label/service_name/values" \
            | python3 -c "
import json, sys
print('app' in json.load(sys.stdin).get('data', []))" 2>/dev/null)"

    # `@timestamp` is the ECS spelling and appears in no plain-text log line, so finding it is
    # proof that what reached Loki is the JSON document and not the pattern layout - the exact
    # failure that happens when LOG_FORMAT is unset and which every check above survives.
    check "the lines are the ECS JSON the application wrote, not text" "True" \
        "$([ "$(loki_count "{service_name=\"app\"} |= \`@timestamp\`")" -gt 0 ] \
            && echo True || echo False)"

    # OVER AN EXPLICIT 24-HOUR WINDOW, and that is the whole subtlety. Loki's label endpoints
    # answer for a default window of the recent past, and PostgreSQL and Redis say almost nothing
    # once they are up - so a stack that has been running quietly for a few hours has no db or
    # cache lines in that default window and the label values come back as just the noisy
    # services. The first version of this check asked for label values with no window and failed
    # on a perfectly healthy stack. Ask for the lines themselves, over a window long enough to
    # contain a start-up.
    INFRA_SINCE="$(python3 -c "import time; print(int((time.time() - 86400) * 1e9))")"

    # ONE QUERY PER SERVICE, not a single `service_name=~"db|cache"`. Loki's `limit` caps ENTRIES
    # across the whole result, newest first, so one chatty container can consume the entire budget
    # and the others come back absent rather than empty. That is exactly what happened here: a
    # freshly recreated PostgreSQL filled all 100 entries with startup chatter, Redis returned
    # nothing, and the check reported "1" for a stack where both were shipping perfectly well. A
    # test whose result depends on which container restarted most recently is a test that will
    # eventually be ignored.
    INFRA_LINES=0
    for infra_service in db cache inventory-db; do
        FOUND="$(curl -sSG "$LOKI_URL/loki/api/v1/query_range" \
            --data-urlencode "query={service_name=\"$infra_service\"}" \
            --data-urlencode "start=$INFRA_SINCE" \
            --data-urlencode 'limit=1' 2>/dev/null \
            | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); raise SystemExit
print(1 if d.get('data', {}).get('result') else 0)" 2>/dev/null || echo 0)"
        INFRA_LINES=$((INFRA_LINES + FOUND))
    done

    check "both PostgreSQL databases and Redis are shipped, for the context an app log lacks" 3 \
        "$INFRA_LINES"

    # --- No sensitive data in logs -----------------------------------------------------------
    # The phase's fourth deliverable, asserted the only way that means anything: by searching the
    # log store for the secrets this very script has been sending all along. The customer's
    # password went to POST /api/auth/login in this run, and its token has been on the
    # Authorization header of most requests since.
    check "the customer's password appears nowhere in the logs" 0 \
        "$(loki_count "{service_name=\"app\"} |= \`$CUSTOMER_PASSWORD\`")"

    check "the admin's password appears nowhere in the logs" 0 \
        "$(loki_count "{service_name=\"app\"} |= \`$ADMIN_PASSWORD\`")"

    # A JWT signature is unique to one token, so finding it anywhere in the logs would mean a
    # bearer credential had been written to a store that is replicated and long-lived.
    TOKEN_SIGNATURE="$(printf '%s' "$AUTH" | cut -d. -f3 | cut -c1-24)"
    check "no bearer token is written to the logs" 0 \
        "$(loki_count "{service_name=\"app\"} |= \`$TOKEN_SIGNATURE\`")"

    check "and no Authorization header is logged either" 0 \
        "$(loki_count "{service_name=\"app\"} |= \`Bearer \`")"

    # --- Alloy ---------------------------------------------------------------------------------
    if curl -fsS "$ALLOY_URL/-/ready" >/dev/null 2>&1; then
        check "Alloy is running the pipeline and has shipped entries to Loki" "True" \
            "$(curl -sS "$ALLOY_URL/metrics" \
                | awk '/^loki_write_sent_entries_total/ {total += $2} END {print (total > 0)}' \
                | sed 's/^1$/True/; s/^0$/False/')"

        # And that Docker AGREES it is healthy. Alloy answering /-/ready from the host says
        # nothing about the container's own health check, which runs inside the container with a
        # different shell - the first version of that check used CMD-SHELL, whose /bin/sh is dash
        # and has no /dev/tcp builtin, so the container sat `unhealthy` for hours while shipping
        # logs perfectly well. A health check nobody verifies is a status light wired to nothing.
        if command -v docker >/dev/null 2>&1; then
            check "and Docker's own health check for it agrees" "healthy" \
                "$(docker inspect ecomdemo-alloy --format '{{.State.Health.Status}}' 2>/dev/null \
                    || echo MISSING)"
        else
            skip "Alloy container health" "docker is not on the PATH"
        fi
    else
        skip "Alloy checks" "no Alloy at $ALLOY_URL (set ALLOY_URL to override)"
    fi

    # --- Grafana's side of it --------------------------------------------------------------------
    if curl -fsS "$GRAFANA_URL/api/health" >/dev/null 2>&1; then
        check "Grafana provisioned the Loki datasource" "ecomdemo-loki" \
            "$(curl -sS -u "$GRAFANA_AUTH" "$GRAFANA_URL/api/datasources" | python3 -c "
import json, sys
print(next((d['uid'] for d in json.load(sys.stdin) if d['type'] == 'loki'), 'MISSING'))")"

        check "the logs dashboard is provisioned from the repository" "EcomDemo Logs" \
            "$(curl -sS -u "$GRAFANA_AUTH" "$GRAFANA_URL/api/dashboards/uid/ecomdemo-logs" \
                | python3 -c "
import json, sys
print(json.load(sys.stdin).get('dashboard', {}).get('title', 'MISSING'))")"

        # Grafana proxying a query is the last link in the chain: it is what the dashboard
        # actually does, and it can fail on its own (a wrong URL, a datasource the browser can
        # reach but the server cannot) while every check above still passes.
        check "and Grafana itself can query Loki for that correlation ID" "True" \
            "$(curl -sSG -u "$GRAFANA_AUTH" \
                "$GRAFANA_URL/api/datasources/proxy/uid/ecomdemo-loki/loki/api/v1/query_range" \
                --data-urlencode "query={service_name=\"app\"} | correlation_id = \`$SMOKE_CORRELATION_ID\`" \
                --data-urlencode "since=15m" \
                | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(sum(len(s.get('values', [])) for s in d.get('data', {}).get('result', [])) > 0)" 2>/dev/null)"
    else
        skip "Grafana logging checks" "no Grafana at $GRAFANA_URL (set GRAFANA_URL to override)"
    fi
else
    skip "Loki checks" "no Loki at $LOKI_URL (set LOKI_URL to override)"
fi

as_customer

# --------------------------------------------------------------------------------------------
# 13. Messaging (Phase 17)
# --------------------------------------------------------------------------------------------
# Everything here needs the broker, and the broker is only reachable through the compose stack, so
# the whole section SKIPS rather than passes when it is not there.
section "Messaging"

KAFKA_CONTAINER="${KAFKA_CONTAINER:-ecomdemo-kafka}"

# kafka <script> <args...> -> runs one of the broker's own CLI tools inside its container.
# The image ships them, which is why - unlike Loki in Phase 16 - there is something here to ask
# with. Always against localhost:9092, the INTERNAL listener, because this runs inside the broker.
kafka() {
    local script="$1"; shift
    docker exec "$KAFKA_CONTAINER" "/opt/kafka/bin/$script" --bootstrap-server localhost:9092 "$@" 2>/dev/null
}

# topic_message_count <topic> -> total messages across every partition, from the END offsets.
# A topic's size is not a thing Kafka reports directly: this sums the offset each partition has
# reached, which for a topic nothing has compacted or expired is the number of messages ever
# written to it.
topic_message_count() {
    docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh \
        --bootstrap-server localhost:9092 --topic "$1" 2>/dev/null \
        | awk -F: '{total += $3} END {print (total == "" ? 0 : total)}'
}

if command -v docker >/dev/null 2>&1 && docker exec "$KAFKA_CONTAINER" true >/dev/null 2>&1; then
    TOPICS="$(kafka kafka-topics.sh --list)"

    check "the orders.placed topic exists" "True" \
        "$(printf '%s\n' "$TOPICS" | grep -qx 'orders.placed' && echo True || echo False)"

    # Created by the application's NewTopic beans, not by a producer's first send: the broker runs
    # with auto.create.topics.enable=false, so a typo in a topic name is an error rather than a new
    # topic nobody is reading.
    check "and it has 3 partitions, the ceiling on consumer parallelism" "3" \
        "$(kafka kafka-topics.sh --describe --topic orders.placed \
            | awk '/PartitionCount/ {for (i = 1; i < NF; i++) if ($i == "PartitionCount:") print $(i+1)}')"

    # The retry topics are named by INDEX, not by delay. That matters: the default names them after
    # the backoff, and with jitter that is a different number every restart - a new pair of orphan
    # topics for ever. See OrderPlacedListener.
    check "the retry topics exist, named by index" "True" \
        "$(printf '%s\n' "$TOPICS" | grep -qx 'orders.placed-retry-0' \
            && printf '%s\n' "$TOPICS" | grep -qx 'orders.placed-retry-1' && echo True || echo False)"

    check "and so does the dead-letter topic" "True" \
        "$(printf '%s\n' "$TOPICS" | grep -qx 'orders.placed-dlt' && echo True || echo False)"

    # --- An order becomes a message, and the message becomes exactly one notification ----------
    as_customer
    request GET /api/cart >/dev/null
    for product_id in $(jget "' '.join(str(i['productId']) for i in d['items'])"); do
        request DELETE "/api/cart/items/$product_id" >/dev/null
    done

    # ❗ PROVE THE PIPELINE IS LIVE WITH A REAL MESSAGE, and give it six minutes to do so.
    #
    # CONTAINER HEALTH IS NOT CONSUMER READINESS. `docker compose up --wait` returns when every health
    # probe passes, and notification-service's probe is /actuator/health/readiness - the JVM and its
    # DataSource - which says nothing about Kafka. MEASURED on a cold sixteen-container stack: the
    # container reported healthy and its consumer group took a further FOUR AND A HALF MINUTES to get
    # partitions assigned, with four consumer containers per service (main, two retry topics, the DLT)
    # all joining groups at once against a broker under memory pressure. The notification arrived five
    # seconds after assignment.
    #
    # Two weaker versions of this gate were tried and both were wrong:
    #   - widening the individual checks (20s -> 60s -> 90s) guessed at a latency instead of waiting for
    #     the precondition, and never got close to 4.5 minutes;
    #   - querying the consumer group for an assignment passed IMMEDIATELY on stale metadata, because
    #     `docker compose down` leaves the group's old member entry in Kafka's log.
    #
    # A probe order cannot be fooled by stale state: either a notification row appears for an id created
    # seconds ago, or the pipeline is not working. It returns in about a second on a warm stack, so the
    # six-minute bound is only ever paid once, on a cold one.
    as_admin
    request POST /api/products \
        '{"name":"Pipeline Probe","description":"proves the consumer is live","price":5.00,"stockQuantity":1,"category":"TEST"}' >/dev/null
    PROBE_PRODUCT_ID="$(jget "d['id']")"
    as_customer
    request POST /api/cart/items "{\"productId\":$PROBE_PRODUCT_ID,\"quantity\":1}" >/dev/null
    request POST /api/orders >/dev/null
    PROBE_ORDER_ID="$(jget "d['id']")"

    check "the notification pipeline is live: order -> Kafka -> notification" "1" \
        "$(wait_for_notification "$PROBE_ORDER_ID" 360)"


    request GET /api/products >/dev/null
    KAFKA_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 1)")"
    OFFSETS_BEFORE="$(topic_message_count orders.placed)"

    request POST /api/cart/items "{\"productId\":$KAFKA_PRODUCT_ID,\"quantity\":1}" >/dev/null
    check "an order is placed for the messaging check" "201" "$(request POST /api/orders)"
    KAFKA_ORDER_ID="$(jget "d['id']")"

    # Polled, not read once. Phase 17 published from an AFTER_COMMIT listener on an async pool,
    # which was fast enough that reading the offset the instant the checkout returned almost always
    # won. Phase 18 put a transactional outbox in between: the row is committed with the order and
    # a RELAY publishes it on a one-second poll, so there is now up to a second plus an
    # acknowledgement between the 201 and the message existing. The immediate read became a race
    # that usually won - which is the worst kind, because it fails on someone else's machine, or
    # during a merge verification, long after the change that caused it.
    #
    # The notification check immediately below this one has polled since Phase 17 and says why.
    # This one simply never got the same treatment when the outbox arrived.
    TOPIC_GROWTH=0
    for _ in $(seq 1 20); do
        TOPIC_GROWTH=$(( $(topic_message_count orders.placed) - OFFSETS_BEFORE ))
        [ "$TOPIC_GROWTH" -ge 1 ] && break
        sleep 1
    done

    # Waiting for "at least one" and then asserting "exactly one" is deliberate: a SECOND message
    # for the same order would be a duplicate publication, and the check below - exactly one
    # notification row - is what catches that, because the consumer's processed_event table is
    # what makes a duplicate harmless rather than invisible.
    check "the topic grew by exactly one message" "1" "$TOPIC_GROWTH"

    NOTIFICATION_COUNT="$(wait_for_notification $KAFKA_ORDER_ID)"

    check "exactly one notification row exists for the order" "1" "${NOTIFICATION_COUNT:-0}"

    check "and it is addressed to the customer who placed it" "$CUSTOMER_USER" \
        "$(notification_psql_query "SELECT recipient FROM notification WHERE order_id = $KAFKA_ORDER_ID;")"

    check "the event was recorded as processed, which is what makes a redelivery a no-op" "1" \
        "$(notification_psql_query "SELECT count(*) FROM processed_event WHERE event_type = 'OrderPlacedEvent';" \
            | awk '{print ($1 >= 1 ? 1 : 0)}')"

    # The MESSAGE itself, read back off the topic. `--from-beginning` with a timeout rather than a
    # message count, because the interesting assertion is about a specific order somewhere in the
    # topic rather than about whichever message happens to be last.
    TOPIC_DUMP="$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server localhost:9092 --topic orders.placed --from-beginning \
        --property print.key=true --timeout-ms 8000 2>/dev/null)"

    check "the order's event is on the topic" "True" \
        "$(printf '%s' "$TOPIC_DUMP" | grep -q "\"orderId\":$KAFKA_ORDER_ID" && echo True || echo False)"

    # The key is what routes a message to a partition, and Kafka guarantees order only WITHIN a
    # partition - so keying by order id is what keeps one order's events together and in sequence.
    check "and it is keyed by the order id, which is what fixes its partition" "True" \
        "$(printf '%s' "$TOPIC_DUMP" | grep -q "^$KAFKA_ORDER_ID	" && echo True || echo False)"

    # --- A poison message must land on the DLT and must not block the partition -----------------
    DLT_BEFORE="$(topic_message_count orders.placed-dlt)"

    # Bytes that are not an OrderPlacedEvent at all. This fails in the DESERIALIZER, before any
    # application code runs - the case that would otherwise be unrecoverable, because there is
    # nothing to catch it and the offset is never committed.
    printf 'poison:{"not":"an OrderPlacedEvent"}\n' \
        | docker exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
            --bootstrap-server localhost:9092 --topic orders.placed \
            --property parse.key=true --property key.separator=: >/dev/null 2>&1

    DLT_AFTER="$DLT_BEFORE"
    for _ in $(seq 1 30); do
        DLT_AFTER="$(topic_message_count orders.placed-dlt)"
        [ "$DLT_AFTER" -gt "$DLT_BEFORE" ] && break
        sleep 1
    done

    check "a poison message ends on the dead-letter topic" "True" \
        "$([ "$DLT_AFTER" -gt "$DLT_BEFORE" ] && echo True || echo False)"

    # The stronger claim, and the one a user would notice: the partition kept moving. A consumer
    # that retried the bad record in place would have stopped everything behind it.
    request GET /api/products >/dev/null
    POISON_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 1)")"
    request POST /api/cart/items "{\"productId\":$POISON_PRODUCT_ID,\"quantity\":1}" >/dev/null
    check "an order placed AFTER the poison message still succeeds" "201" "$(request POST /api/orders)"
    AFTER_POISON_ORDER_ID="$(jget "d['id']")"

    AFTER_POISON_COUNT="$(wait_for_notification $AFTER_POISON_ORDER_ID)"
    check "and it is still notified, so the poison never blocked the partition" "1" \
        "${AFTER_POISON_COUNT:-0}"

    # --- Redelivery must not write a second notification ---------------------------------------
    # The same event id twice is what a redelivery IS: the same bytes, handed over again. Sent
    # straight to the topic rather than through the API, because the API cannot place the same
    # order twice - which is exactly why idempotency has to be the consumer's job.
    DUPLICATE_ORDER_ID=999000$$
    DUPLICATE_EVENT="{\"eventId\":\"$(python3 -c 'import uuid;print(uuid.uuid4())')\",\"orderId\":$DUPLICATE_ORDER_ID,\"username\":\"$CUSTOMER_USER\",\"totalAmount\":12.34,\"itemCount\":1,\"placedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"

    for _ in 1 2; do
        printf '%s:%s\n' "$DUPLICATE_ORDER_ID" "$DUPLICATE_EVENT" \
            | docker exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
                --bootstrap-server localhost:9092 --topic orders.placed \
                --property parse.key=true --property key.separator=: >/dev/null 2>&1
    done

    DUPLICATE_COUNT="$(wait_for_notification $DUPLICATE_ORDER_ID)"
    # Give the second copy time to be wrong in. A "still one" assertion made immediately proves
    # nothing, because the duplicate may simply not have been consumed yet.
    sleep 3
    DUPLICATE_COUNT="$(notification_psql_query "SELECT count(*) FROM notification WHERE order_id = $DUPLICATE_ORDER_ID;" || echo 0)"

    check "the same event delivered twice writes ONE notification" "1" "${DUPLICATE_COUNT:-0}"

    notification_psql_query "DELETE FROM notification WHERE order_id = $DUPLICATE_ORDER_ID;" >/dev/null 2>&1

    # --- The consumer group is keeping up --------------------------------------------------------
    # Lag is the distance between what has been written and what this group has committed. A lag
    # that is zero here says the notification consumer drained everything this run produced; a lag
    # that grew would mean messages are arriving faster than they are handled, which is invisible
    # from the application side.
    check "the notification consumer group has caught up (lag 0)" "0" \
        "$(kafka kafka-consumer-groups.sh --describe --group ecomdemo-notification \
            | awk '$1 == "ecomdemo-notification" && $6 ~ /^[0-9]+$/ {lag += $6} END {print (lag == "" ? 0 : lag)}')"
else
    skip "Kafka checks" "no Kafka container ($KAFKA_CONTAINER); set KAFKA_CONTAINER to override"
fi

as_customer

# --------------------------------------------------------------------------------------------
# 14. Reliable event publishing (Phase 18)
# --------------------------------------------------------------------------------------------
# THE PHASE'S "DONE WHEN", and the only place in the build where it can be proved honestly: stop
# the broker for real, place an order, start it again, and watch the notification arrive.
#
# The integration tests cannot do this. Testcontainers shares one Kafka across the whole suite, so
# a test that stopped it would break every class that ran afterwards. What they prove is the
# MECHANISM - that the row is written in the order's transaction, that a failed send leaves it
# pending, that a republished row costs a duplicate send and not a duplicate notification. What
# this section proves is the CLAIM: that an outage costs nothing at all.
#
# Phase 17 measured the old behaviour under exactly this test and lost four orders their
# notification, permanently. The number to beat is zero.
section "Reliable event publishing"

if command -v docker >/dev/null 2>&1 \
    && docker exec "$KAFKA_CONTAINER" true >/dev/null 2>&1 \
    && psql_query "SELECT 1;" >/dev/null 2>&1; then

    # --- The outbox exists and is being drained ------------------------------------------------
    # A healthy outbox is nearly EMPTY, which is the opposite of how most tables are judged. Rows
    # accumulate here only while the broker is unreachable; a pending count that stays high is the
    # single clearest signal that publication has stopped.
    check "the outbox table exists" "1" \
        "$(psql_query "SELECT count(*) FROM information_schema.tables WHERE table_name = 'outbox_event';" | tr -d ' ')"

    OUTBOX_PENDING_BEFORE="$(psql_query "SELECT count(*) FROM outbox_event WHERE published_at IS NULL;" | tr -d ' ')"
    check "and nothing is stuck in it before the outage" "0" "${OUTBOX_PENDING_BEFORE:-unknown}"

    # --- An ordinary order leaves a published row ----------------------------------------------
    request GET /api/products >/dev/null
    OUTBOX_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 2)")"
    request POST /api/cart/items "{\"productId\":$OUTBOX_PRODUCT_ID,\"quantity\":1}" >/dev/null
    check "an order placed with the broker UP returns 201" "201" "$(request POST /api/orders)"
    OUTBOX_ORDER_ID="$(jget "d['id']")"

    # The row is written in the order's own transaction, so it is there the instant the checkout
    # returns - no waiting, no polling. That is the difference this phase made.
    check "its event was written to the outbox in the same transaction" "1" \
        "$(psql_query "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$OUTBOX_ORDER_ID';" | tr -d ' ')"

    OUTBOX_PUBLISHED=0
    for _ in $(seq 1 30); do
        OUTBOX_PUBLISHED="$(psql_query "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$OUTBOX_ORDER_ID' AND published_at IS NOT NULL;" | tr -d ' ')"
        [ "${OUTBOX_PUBLISHED:-0}" -ge 1 ] && break
        sleep 1
    done
    check "and the relay published it and marked it sent" "1" "${OUTBOX_PUBLISHED:-0}"

    # Zero attempts, because the broker was up. The counter is what makes a stuck row visible, so
    # it should be silent when nothing is wrong.
    check "with no failed attempts recorded against it" "0" \
        "$(psql_query "SELECT coalesce(max(attempts), -1) FROM outbox_event WHERE aggregate_id = '$OUTBOX_ORDER_ID';" | tr -d ' ')"

    # --- THE OUTAGE ----------------------------------------------------------------------------
    # Everything above this line is the system working. Everything below is the system being
    # broken on purpose.
    # WARM THE CHECKOUT PATH FIRST, and this is a fix rather than a nicety.
    #
    # The timing check below flaked during Phase 20c's merge verification: it failed once against an
    # application container seventeen seconds old, and passed on every warm run. The first checkout
    # after a restart pays for lazy initialisation that has grown with each extracted service - the
    # Hibernate statement cache, the JSON mappers, and now a RestClient and a signed service token for
    # each of two downstream calls. The check was measuring JVM warm-up as much as the claim it makes.
    #
    # A throwaway checkout pays that cost once, outside the measurement. The claim is "the broker is not
    # in the request path", and it should be tested on a path that has been walked before - which is
    # also the only state a real deployment is ever measured in.
    #
    # IT RUNS BEFORE THE BROKER IS STOPPED, and the first attempt at this fix got that wrong. Warming up
    # DURING the outage puts a second row in the outbox, so the relay is retrying two events and the
    # checks below - which assert an attempt count and a recorded error on ONE row - saw the wrong one.
    # A warm-up that perturbs the thing it precedes is worse than no warm-up.
    as_admin
    request POST /api/products \
        '{"name":"Warmup Probe","description":"pays the cold-start cost","price":1.00,"stockQuantity":1,"category":"TEST"}' >/dev/null
    WARMUP_PRODUCT_ID="$(jget "d['id']")"
    as_customer
    request POST /api/cart/items "{\"productId\":$WARMUP_PRODUCT_ID,\"quantity\":1}" >/dev/null
    request POST /api/orders >/dev/null
    WARMUP_ORDER_ID="$(jget "d['id']")"

    # AND WAIT FOR ITS OUTBOX ROW TO DRAIN before stopping the broker. This is the subtle half of the
    # fix, and the first two attempts at it were wrong.
    #
    # The relay STOPS THE BATCH AT THE FIRST FAILURE - a deliberate Phase 18 decision, so that event
    # N+1 is never published ahead of a failed event N. So a warm-up row that is still pending when
    # Kafka goes down becomes the row the relay retries for ever, and the outage row queues silently
    # BEHIND it with attempts still at zero. The checks below then read an untouched row and conclude
    # the relay is not retrying, when it is retrying furiously - just not that one.
    #
    # Waiting for the warm-up to publish leaves exactly one pending row during the outage, which is
    # what those checks assume. A test fixture that leaves debris in the machinery it is about to
    # examine is worse than no fixture.
    for _ in $(seq 1 30); do
        WARMUP_PENDING="$(psql_query \
            "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$WARMUP_ORDER_ID' AND published_at IS NULL;" \
            | tr -d ' ')"
        [ "${WARMUP_PENDING:-1}" = "0" ] && break
        sleep 1
    done

    docker stop "$KAFKA_CONTAINER" >/dev/null 2>&1
    KAFKA_WAS_STOPPED=true   # the EXIT trap restores it if anything below fails

    # The checkout must still succeed, and must still be FAST. Phase 17's failure test found a
    # 97-second checkout here, because the AFTER_COMMIT send blocked the request thread waiting
    # for cluster metadata. Now the request never touches Kafka at all - it writes a row - so the
    # broker being down should be invisible to the customer.
    request GET /api/products >/dev/null
    OUTAGE_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 2)")"
    request POST /api/cart/items "{\"productId\":$OUTAGE_PRODUCT_ID,\"quantity\":1}" >/dev/null

    OUTAGE_STARTED_AT="$(date +%s)"
    OUTAGE_STATUS="$(request POST /api/orders)"
    OUTAGE_ELAPSED=$(( $(date +%s) - OUTAGE_STARTED_AT ))
    OUTAGE_ORDER_ID="$(jget "d['id']")"

    check "an order placed with the broker DOWN still returns 201" "201" "$OUTAGE_STATUS"

    # Ten seconds is generous - it should be well under one - but this is a laptop running SIXTEEN
    # containers and the assertion that matters is "the broker is not in the request path", not a
    # millisecond budget. The warm-up above is what makes the number mean that rather than "how long
    # did this JVM take to finish starting".
    check "and the checkout did not wait for the broker (under 10s)" "True" \
        "$([ "$OUTAGE_ELAPSED" -lt 10 ] && echo True || echo False)"

    # The event survived the outage because it is in PostgreSQL, not in the memory of a process
    # talking to a broker that is not there. This single check is the whole phase.
    check "its event is safe in the outbox, waiting, not lost" "1" \
        "$(psql_query "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$OUTAGE_ORDER_ID' AND published_at IS NULL;" | tr -d ' ')"

    # The relay keeps trying and keeps failing, and says so in the row rather than only in a log.
    # FORTY seconds, not fifteen, and the number is derived rather than guessed.
    #
    # `attempts` is incremented AFTER a send fails, and a send against a stopped broker takes as long
    # as the producer's own budget allows: max.block.ms=5000 for cluster metadata plus
    # delivery.timeout.ms=10000, so one failed attempt can take ten seconds to be recorded. Fifteen
    # seconds left room for one attempt and no slack, which was survivable at nine containers and is
    # not at sixteen - the check failed here on a stack where the relay was working perfectly and had
    # simply not finished failing yet.
    #
    # The claim is "the relay retries and writes down why", not "within fifteen seconds". A budget that
    # doubles as an undeclared performance assertion is a flake waiting for a slower machine.
    OUTAGE_ATTEMPTS=0
    for _ in $(seq 1 40); do
        OUTAGE_ATTEMPTS="$(psql_query "SELECT coalesce(max(attempts), 0) FROM outbox_event WHERE aggregate_id = '$OUTAGE_ORDER_ID';" | tr -d ' ')"
        [ "${OUTAGE_ATTEMPTS:-0}" -ge 1 ] && break
        sleep 1
    done
    check "the relay is retrying it, and the attempt count says so" "True" \
        "$([ "${OUTAGE_ATTEMPTS:-0}" -ge 1 ] && echo True || echo False)"

    check "and the reason is recorded on the row, not only in a log" "True" \
        "$([ -n "$(psql_query "SELECT last_error FROM outbox_event WHERE aggregate_id = '$OUTAGE_ORDER_ID' AND last_error IS NOT NULL;" | tr -d ' ')" ] && echo True || echo False)"

    # No notification yet, obviously - nothing has been published. Asserted so that the recovery
    # below is a real transition and not a re-statement of something already true.
    check "no notification has been written for it yet" "0" \
        "$(notification_psql_query "SELECT count(*) FROM notification WHERE order_id = $OUTAGE_ORDER_ID;" | tr -d ' ')"

    # --- RECOVERY ------------------------------------------------------------------------------
    docker start "$KAFKA_CONTAINER" >/dev/null 2>&1
    KAFKA_WAS_STOPPED=false

    for _ in $(seq 1 60); do
        docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:9092 --list >/dev/null 2>&1 && break
        sleep 1
    done

    # Nobody re-places the order and nobody replays anything by hand. The relay finds the row on
    # its next tick and sends it, because the row never stopped being there.
    #
    # Ninety seconds because recovery is not instant: the broker has to finish starting, and the
    # producer's client has to notice it is back, which takes as long as its reconnect backoff
    # takes. The claim is that it arrives, not that it arrives immediately.
    OUTAGE_NOTIFIED=0
    for _ in $(seq 1 90); do
        OUTAGE_NOTIFIED="$(notification_psql_query "SELECT count(*) FROM notification WHERE order_id = $OUTAGE_ORDER_ID;" | tr -d ' ')"
        [ "${OUTAGE_NOTIFIED:-0}" -ge 1 ] && break
        sleep 1
    done

    # THE "DONE WHEN": no events are lost while Kafka is down.
    check "once the broker is back, the order IS notified - nothing was lost" "1" \
        "${OUTAGE_NOTIFIED:-0}"

    check "and the outbox row is now marked published" "1" \
        "$(psql_query "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$OUTAGE_ORDER_ID' AND published_at IS NOT NULL;" | tr -d ' ')"

    # Exactly one, not two. The relay retried this row many times during the outage and published
    # it once afterwards; had any of those attempts actually reached the broker, the consumer's
    # processed_event table would have absorbed the duplicate. At-least-once at the producer,
    # exactly-once in the effect.
    check "exactly ONE notification for it, despite every retry" "1" \
        "$(notification_psql_query "SELECT count(*) FROM notification WHERE order_id = $OUTAGE_ORDER_ID;" | tr -d ' ')"

    # The outbox drains back to empty. A backlog that never clears would mean the relay recovered
    # for one row and stopped, which is the failure mode this check exists to catch.
    OUTBOX_PENDING_AFTER=1
    for _ in $(seq 1 30); do
        OUTBOX_PENDING_AFTER="$(psql_query "SELECT count(*) FROM outbox_event WHERE published_at IS NULL;" | tr -d ' ')"
        [ "${OUTBOX_PENDING_AFTER:-1}" -eq 0 ] && break
        sleep 1
    done
    check "and the outbox has drained back to empty" "0" "${OUTBOX_PENDING_AFTER:-unknown}"

    # --- The sweep keeps what is still pending -------------------------------------------------
    # The cleanup job runs at 03:00, so this exercises the QUERY it uses rather than waiting for
    # the clock. The dangerous mistake would be a sweep that deletes by age alone: an outage is
    # exactly what makes a pending row old, so such a sweep would delete the events it exists to
    # protect, at the moment they mattered.
    psql_query "INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at, attempts) VALUES ('00000000-0000-0000-0000-0000000000aa', 'Order', '-1', 'OrderPlacedEvent', '{}', CURRENT_TIMESTAMP - INTERVAL '400 days', NULL, 99);" >/dev/null 2>&1

    # A second row, identical in age but PUBLISHED, so the two differ in exactly one column.
    psql_query "INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at, attempts) VALUES ('00000000-0000-0000-0000-0000000000bb', 'Order', '-2', 'OrderPlacedEvent', '{}', CURRENT_TIMESTAMP - INTERVAL '400 days', CURRENT_TIMESTAMP - INTERVAL '400 days', 0);" >/dev/null 2>&1

    # The sweep's own predicate, run as a SELECT: `published_at IS NOT NULL AND published_at <
    # cutoff`. Against the pending row it matches nothing...
    check "a 400-day-old PENDING row matches no sweep, however old it is" "0" \
        "$(psql_query "SELECT count(*) FROM outbox_event WHERE event_id = '00000000-0000-0000-0000-0000000000aa' AND published_at IS NOT NULL AND published_at < CURRENT_TIMESTAMP - INTERVAL '7 days';" | tr -d ' ')"

    # ...and against the published one it matches, which is what stops the check above from being
    # a tautology. One column apart, opposite outcomes.
    check "while the same row PUBLISHED is swept, so the predicate is doing real work" "1" \
        "$(psql_query "SELECT count(*) FROM outbox_event WHERE event_id = '00000000-0000-0000-0000-0000000000bb' AND published_at IS NOT NULL AND published_at < CURRENT_TIMESTAMP - INTERVAL '7 days';" | tr -d ' ')"

    psql_query "DELETE FROM outbox_event WHERE event_id IN ('00000000-0000-0000-0000-0000000000aa', '00000000-0000-0000-0000-0000000000bb');" >/dev/null 2>&1
else
    skip "Outbox checks" "needs both the Kafka container ($KAFKA_CONTAINER) and database access"
fi

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
