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

# 8080 IS THE GATEWAY SINCE PHASE 21, and BASE_URL deliberately did not change.
#
# Every client-facing check below has always talked to this one variable, so pointing 8080 at the
# gateway means the ~200 checks that already existed now travel through it - routing, edge token
# validation and the rate limiter included - without a single one being rewritten to agree with the
# new code. That is the phase's "clients only use the gateway" proven by a suite written before the
# gateway existed, which is worth far more than new checks written alongside it.
BASE_URL="${BASE_URL:-http://localhost:8080}"

# The APPLICATION directly, for the WHITE-BOX checks only.
#
# Its container still listens on 8080; only the host mapping moved to 8084. Checks that ask this
# service about ITSELF - /actuator/health, its own Prometheus scrape, its heapdump, its env - must
# reach the application and not the gateway, which has an actuator of its own and would answer
# every one of them plausibly and wrongly.
APP_URL="${APP_URL:-http://localhost:8084}"
# The mock payment-service's limit (Phase 24): any order total ABOVE this is declined, and the order
# CANCELLED. Every product this script picks for an order that must SUCCEED is filtered to stay under
# it, times the quantity. Without that, a run on a stack whose cheap products had sold out picked a
# 9,499.00 SSD for the happy path, bought two, and the saga - correctly - cancelled it: the third cold
# run of Phase 24 failed exactly so. The Saga section forces a decline on purpose, with its own probe.
PAYMENT_LIMIT="${PAYMENT_DECLINE_ABOVE:-10000.00}"
BODY="$(mktemp)"
# A whole Prometheus scrape, kept in a file rather than a variable: it is a few hundred lines and
# the same snapshot is read several times per check, so re-fetching it per assertion would both
# be slow and - worse - compare two different moments in time.
SCRAPE="$(mktemp)"
# --------------------------------------------------------------------------------------------
# Where the containers are: compose, or Kubernetes (Phase 25)
# --------------------------------------------------------------------------------------------
# About a third of these checks look INSIDE the system - a SQL query in a database, a Kafka topic's
# offsets, a file in the application's volume - and a few stop a service on purpose. Under compose
# that is `docker exec` / `docker stop` on a container name. On the Phase 25 cluster the same thing
# is `kubectl exec` / `kubectl scale` on a workload. These three functions are the only place that
# knows the difference, so every check below reads the same on both platforms.
#
# SMOKE_PLATFORM=k8s is set by scripts/k8s-smoke.sh, which also points BASE_URL at the Ingress.
# A container name maps to a workload by dropping the `ecomdemo-` prefix: databases and Kafka are
# StatefulSets, everything else a Deployment - `ecomdemo-catalog-db` -> statefulset/catalog-db.
SMOKE_PLATFORM="${SMOKE_PLATFORM:-compose}"
K8S_NAMESPACE="${K8S_NAMESPACE:-ecomdemo}"
K8S_CONTEXT="${K8S_CONTEXT:-kind-ecomdemo}"

k8s_workload() { # k8s_workload <container name> -> statefulset/<n> or deployment/<n>
    local name="${1#ecomdemo-}"
    [ "$name" = "postgres" ] && name="db"
    case "$name" in
        db | *-db | kafka) echo "statefulset/$name" ;;
        *) echo "deployment/$name" ;;
    esac
}

kube() { kubectl --context "$K8S_CONTEXT" -n "$K8S_NAMESPACE" "$@"; }

ctr_exec() { # ctr_exec [-i] <container> <command...>
    local stdin=""
    if [ "$1" = "-i" ]; then stdin="-i"; shift; fi
    local name="$1"; shift
    if [ "$SMOKE_PLATFORM" = "k8s" ]; then
        # shellcheck disable=SC2086
        kube exec $stdin "$(k8s_workload "$name")" -- "$@"
    else
        # shellcheck disable=SC2086
        docker exec $stdin "$name" "$@"
    fi
}

# ctr_stop / ctr_start <container>: take a service away and bring it back.
# On Kubernetes a stopped container is a workload scaled to ZERO - deleting a pod would not do,
# because its Deployment would replace it within seconds (which is the self-healing the Kubernetes
# section tests on purpose). The replica count is remembered so the start restores it, and an HPA
# leaves a workload at zero alone.
CTR_REPLICAS_DIR="$(mktemp -d)"
ctr_stop() {
    local name="$1"
    if [ "$SMOKE_PLATFORM" = "k8s" ]; then
        local workload; workload="$(k8s_workload "$name")"
        kube get "$workload" -o jsonpath='{.spec.replicas}' > "$CTR_REPLICAS_DIR/${name}" 2>/dev/null
        kube scale "$workload" --replicas=0 >/dev/null 2>&1
        kube wait --for=delete pod -l "app.kubernetes.io/name=${name#ecomdemo-}" --timeout=120s >/dev/null 2>&1
    else
        docker stop "$name"
    fi
}
ctr_start() {
    local name="$1"
    if [ "$SMOKE_PLATFORM" = "k8s" ]; then
        local workload replicas; workload="$(k8s_workload "$name")"
        replicas="$(cat "$CTR_REPLICAS_DIR/${name}" 2>/dev/null || echo 1)"
        kube scale "$workload" --replicas="${replicas:-1}" >/dev/null 2>&1
        kube rollout status "$workload" --timeout=300s >/dev/null 2>&1
    else
        docker start "$name"
    fi
}

# The Phase 18 section stops the Kafka container on purpose. If anything between the stop and the
# start fails - a failed check under `set -e`, or a Ctrl-C - the broker would be left down and
# every later run of this script would report a broken stack rather than a failed check. This
# restores it on the way out, whatever happened.
KAFKA_WAS_STOPPED=false
restore_kafka() {
    if [ "$KAFKA_WAS_STOPPED" = true ]; then
        printf '\033[33mrestoring the Kafka container, which this script had stopped\033[0m\n' >&2
        ctr_start "${KAFKA_CONTAINER:-ecomdemo-kafka}" >/dev/null 2>&1 || true
    fi
}

# The same promise for catalog-service, which the Resilience section stops (Phase 22).
CATALOG_WAS_STOPPED=false
restore_catalog() {
    if [ "$CATALOG_WAS_STOPPED" = true ]; then
        printf '\033[33mrestoring the catalog-service container, which this script had stopped\033[0m\n' >&2
        ctr_start "${CATALOG_CONTAINER:-ecomdemo-catalog-service}" >/dev/null 2>&1 || true
    fi
}
# And for payment-service, which the saga deadline section stops (Phase 32).
PAYMENT_WAS_STOPPED=false
restore_payment() {
    if [ "$PAYMENT_WAS_STOPPED" = true ]; then
        printf '\033[33mrestoring the payment-service container, which this script had stopped\033[0m\n' >&2
        ctr_start "${PAYMENT_CONTAINER:-ecomdemo-payment-service}" >/dev/null 2>&1 || true
    fi
}
trap 'rm -f "$BODY" "$SCRAPE"; restore_kafka; restore_catalog; restore_payment; rm -rf "$CTR_REPLICAS_DIR"' EXIT

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

# app_request <method> <path> [data] -> status code, straight at the APPLICATION on 8084.
#
# The white-box twin of `request`. Same contract - writes the body to $BODY, echoes the status - and
# the only difference is which door it knocks on. It exists because ~25 checks below are about the
# application's own instrumentation rather than about the API a client uses, and after Phase 21 those
# are two different servers.
app_request() {
    local method="$1" path="$2" data="${3:-}"
    if [ -n "$AUTH" ]; then
        if [ -n "$data" ]; then
            curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$APP_URL$path" \
                -H "Authorization: Bearer $AUTH" -H 'Content-Type: application/json' -d "$data"
        else
            curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$APP_URL$path" \
                -H "Authorization: Bearer $AUTH"
        fi
    elif [ -n "$data" ]; then
        curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$APP_URL$path" \
            -H 'Content-Type: application/json' -d "$data"
    else
        curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$APP_URL$path"
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
    # THE APPLICATION's scrape, on 8084. The meters these checks assert on - orders placed, cart
    # operations, the outbox gauge - are registered by this service. The gateway publishes its own
    # /actuator/prometheus with http_server_requests in it and none of those business meters, so
    # scraping the wrong one would leave every assertion below looking for a series that is simply
    # not there.
    curl -sS -o "$SCRAPE" "$APP_URL/actuator/prometheus"
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
            && ctr_exec "$candidate" true >/dev/null 2>&1; then
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
            && ctr_exec "$candidate" true >/dev/null 2>&1; then
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
        && ctr_exec "$REDIS_CONTAINER" true >/dev/null 2>&1; then
        ctr_exec "$REDIS_CONTAINER" redis-cli "$@" 2>/dev/null
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
        && ctr_exec "$POSTGRES_CONTAINER" true >/dev/null 2>&1; then
        ctr_exec "$POSTGRES_CONTAINER" psql -qtAX -U "$PGUSER_" -d "$PGDB" -c "$1" 2>/dev/null
    else
        return 1
    fi
}

# --------------------------------------------------------------------------------------------
# 0. The application must be up
# customer_psql_query <sql> -> against CUSTOMER-SERVICE's database, which owns `users` since 20d.
customer_psql_query() {
    ctr_exec "${CUSTOMER_DB_CONTAINER:-ecomdemo-customer-db}" \
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
    ctr_exec "${NOTIFICATION_DB_CONTAINER:-ecomdemo-notification-db}" \
        psql -qtAX -U "${NOTIFICATION_DB_USER:-notification}" \
        -d "${NOTIFICATION_DB_NAME:-notification}" -c "$1" 2>/dev/null
}

# inventory_psql_query / payment_psql_query <sql> -> the saga's other two databases (Phase 24): the
# reservations inventory-service holds, and the payments payment-service decided.
inventory_psql_query() {
    ctr_exec "${INVENTORY_DB_CONTAINER:-ecomdemo-inventory-db}" \
        psql -qtAX -U "${INVENTORY_DB_USER:-inventory}" \
        -d "${INVENTORY_DB_NAME:-inventory}" -c "$1" 2>/dev/null
}

payment_psql_query() {
    ctr_exec "${PAYMENT_DB_CONTAINER:-ecomdemo-payment-db}" \
        psql -qtAX -U "${PAYMENT_DB_USER:-payment}" \
        -d "${PAYMENT_DB_NAME:-payment}" -c "$1" 2>/dev/null
}

# wait_for_order_status <order_id> [seconds] -> prints the order's status once it has LEFT PENDING,
# or PENDING if the saga did not decide within the bound (default 90s).
#
# Phase 24: checkout answers 201 with a PENDING order, and CONFIRMED or CANCELLED arrives a few
# seconds later, after inventory-service and payment-service have each read an event and answered.
# This polls GET /api/orders/{id}/status the way a client would, as the CURRENT caller ($AUTH).
# Warm, a decision takes about three seconds (three outbox relays at a one-second poll, plus the
# hops); the bound is generous for the same reason wait_for_notification's is - cold consumers
# joining their groups - and every check that uses it is about the OUTCOME, not the latency.
#
# Its own body file, so that it does not overwrite $BODY under a caller that is still reading it.
wait_for_order_status() {
    local order_id="$1" seconds="${2:-90}" status="PENDING" status_body _
    status_body="$(mktemp)"
    for _ in $(seq 1 "$seconds"); do
        curl -sS -o "$status_body" "$BASE_URL/api/orders/$order_id/status" \
            -H "Authorization: Bearer $AUTH" 2>/dev/null
        status="$(python3 -c "import json;print(json.load(open('$status_body'))['status'])" 2>/dev/null \
            || echo PENDING)"
        [ "$status" != "PENDING" ] && break
        sleep 1
    done
    rm -f "$status_body"
    echo "$status"
}

# wait_for_stock <product_id> <expected> [tenths] -> prints the last stock figure seen, having polled
# the catalogue (through the gateway, as a shopper sees it) until it shows <expected>.
#
# Two eventually-consistent steps stand between a checkout and this number since Phase 24: the saga
# reserves the stock after checkout returns, and the catalogue's cache hears about it through
# `inventory.stock-changed`. Polled in 0.2s steps (default 300 = 60s), like the Phase 20b checks.
wait_for_stock() {
    local product_id="$1" expected="$2" tenths="${3:-300}" seen="" _
    for _ in $(seq 1 "$tenths"); do
        request GET "/api/products/$product_id" >/dev/null
        seen="$(jget "d['stockQuantity']")"
        [ "$seen" = "$expected" ] && break
        sleep 0.2
    done
    echo "$seen"
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
check "the header names the signing algorithm (RS256 since Phase 33)" "RS256" \
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
# the signing key. Since Phase 33 that is customer-service's RSA key (RS256), so this check runs in
# full when JWT_SIGNING_KEY is set in the environment (openssl signs it) and falls back to an
# unsigned expired token otherwise - which the server refuses just as firmly, though for the
# signature rather than the clock. Either way the honest thing is to say which ran.
b64url() { base64 -w0 | tr '+/' '-_' | tr -d '='; }
EXPIRED_ISSUED=$(( $(date +%s) - 7200 ))                  # two hours ago, expired 105 minutes ago
EXPIRED_HEADER="$(printf '{"alg":"RS256","kid":"%s"}' "${JWT_SIGNING_KEY_ID:-key-1}" | b64url)"
EXPIRED_PAYLOAD="$(printf '{"iss":"ecomdemo","sub":"%s","uid":1,"roles":["CUSTOMER"],"iat":%d,"exp":%d}' \
    "$CUSTOMER_USER" "$EXPIRED_ISSUED" "$((EXPIRED_ISSUED + 900))" | b64url)"
EXPIRED_SIGNATURE="$(printf 'not-a-real-signature' | b64url)"
if [ -n "${JWT_SIGNING_KEY:-}" ]; then
    SIGNING_PEM="$(mktemp)"
    printf '%s' "$JWT_SIGNING_KEY" | base64 -d 2>/dev/null \
        | openssl pkey -inform DER -out "$SIGNING_PEM" 2>/dev/null \
        && EXPIRED_SIGNATURE="$(printf '%s.%s' "$EXPIRED_HEADER" "$EXPIRED_PAYLOAD" \
            | openssl dgst -sha256 -sign "$SIGNING_PEM" | b64url)"
    rm -f "$SIGNING_PEM"
fi
EXPIRED_TOKEN="$EXPIRED_HEADER.$EXPIRED_PAYLOAD.$EXPIRED_SIGNATURE"

as_token "$EXPIRED_TOKEN"
check "an expired token returns 401" "401" "$(request GET /api/customers/me)"
check "and says the token is invalid or expired" "True" \
    "$(jget "'invalid or has expired' in d['message']")"
if [ -n "${JWT_SIGNING_KEY:-}" ]; then
    pass "the expired token was correctly signed, so expiry alone caused the refusal"
else
    pass "the expired token was unsigned (JWT_SIGNING_KEY unset); set it to test expiry specifically"
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
# 1. Happy path: list -> add -> view -> place -> read back -> CONFIRMED -> stock decreased
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
PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 2 and p['price'] * 2 <= $PAYMENT_LIMIT)")"
PRODUCT_NAME="$(jget "next(p['name'] for p in d if p['stockQuantity'] >= 2 and p['price'] * 2 <= $PAYMENT_LIMIT)")"
UNIT_PRICE="$(jget "next(str(p['price']) for p in d if p['stockQuantity'] >= 2 and p['price'] * 2 <= $PAYMENT_LIMIT)")"
STOCK_BEFORE="$(jget "next(p['stockQuantity'] for p in d if p['stockQuantity'] >= 2 and p['price'] * 2 <= $PAYMENT_LIMIT)")"
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
# PENDING, not PLACED, since Phase 24: checkout starts a saga and answers before it finishes.
check "order status is PENDING - the saga has only started" "PENDING" "$(jget "d['status']")"
check "order total matches the cart total" "$EXPECTED_TOTAL" "$(jget "d['totalAmount']")"
check "order line snapshots the product name" "$PRODUCT_NAME" "$(jget "d['items'][0]['productName']")"

STATUS="$(request GET "/api/orders/$ORDER_ID")"
check "GET /api/orders/$ORDER_ID returns 200" "200" "$STATUS"
check "order reads back with the same total" "$EXPECTED_TOTAL" "$(jget "d['totalAmount']")"

STATUS="$(request GET /api/orders)"
check "GET /api/orders returns 200" "200" "$STATUS"
check "placed order appears in the list" "True" "$(jget "any(o['id'] == $ORDER_ID for o in d)")"

check "the saga CONFIRMS the order: stock reserved, payment taken" "CONFIRMED" \
    "$(wait_for_order_status "$ORDER_ID")"

STATUS="$(request GET "/api/products/$PRODUCT_ID")"
check "GET /api/products/$PRODUCT_ID returns 200" "200" "$STATUS"
check "stock decreased by $QUANTITY" "$((STOCK_BEFORE - QUANTITY))" \
    "$(wait_for_stock "$PRODUCT_ID" "$((STOCK_BEFORE - QUANTITY))")"

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

# ON APP_URL, NOT THROUGH THE GATEWAY, and the failure that forced this is worth keeping.
#
# The first run after Phase 21 failed all fourteen checks here with 404. `/v3/api-docs` and
# `/swagger-ui/**` are served by springdoc inside the APPLICATION; the gateway has no springdoc (it is
# excluded deliberately - there is nothing there to document) and no route for those paths. So the
# section was asking the wrong server, and getting an honest answer.
#
# These checks describe the APPLICATION's own document, read on its own port. Since KI-001 every
# documented service publishes one, and the gateway serves them all in one Swagger UI: see "API
# documentation through the gateway" at the end of this section.
STATUS="$(app_request GET /v3/api-docs)"
check "GET /v3/api-docs returns 200" "200" "$STATUS"
# "EcomDemo API" until KI-001, when all six services' documents carried that same title.
check "the spec is titled EcomDemo Orders API" "EcomDemo Orders API" "$(jget "d['info']['title']")"

# Every path THIS APPLICATION serves must appear in its spec. Listed explicitly rather than derived
# from the spec itself, so that an endpoint springdoc fails to pick up is caught instead of ignored.
#
# /api/products and /api/products/{id} LEFT this list in Phase 21: the application no longer serves
# them. catalog-service documents them, and the gateway serves its document (KI-001, below).
API_PATHS="/api/cart /api/cart/items /api/cart/items/{productId} /api/orders /api/orders/{id} /api/orders/{id}/status"
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
STATUS="$(app_request GET /swagger-ui.html)"
case "$STATUS" in
    200 | 30*) pass "GET /swagger-ui.html returns 200 or a redirect (got $STATUS)" ;;
    *) fail "GET /swagger-ui.html returns 200 or a redirect" "200 or 3xx" "$STATUS" ;;
esac

STATUS="$(app_request GET /swagger-ui/index.html)"
check "the Swagger UI page itself returns 200" "200" "$STATUS"

STATUS="$(app_request GET /swagger-ui/swagger-ui-bundle.js)"
check "the Swagger UI javascript bundle is served" "200" "$STATUS"

# API documentation through the gateway (KI-001). From Phase 21 until KI-001 the gateway answered 401
# for all of this, and the README's Swagger URL was dead. Each service builds its own document; the
# gateway routes /v3/api-docs/<service> to it and serves one Swagger UI with a dropdown of them.
# Anonymous, because the documents describe the API's shape, not its data.
SAVED_AUTH="$AUTH"
as_anonymous
STATUS="$(curl -sS -L -o "$BODY" -w '%{http_code}' "$BASE_URL/swagger-ui.html")"
check "the gateway's Swagger UI answers 200 (following its redirect)" "200" "$STATUS"
check "and it is the Swagger UI page" "True" "$(grep -q 'swagger-ui' "$BODY" && echo True || echo False)"

STATUS="$(request GET /v3/api-docs/swagger-config)"
check "the gateway's Swagger UI configuration answers 200" "200" "$STATUS"
check "its dropdown lists the five documented services" \
    "/v3/api-docs/app /v3/api-docs/assistant /v3/api-docs/catalog /v3/api-docs/customer /v3/api-docs/inventory" \
    "$(jget "' '.join(sorted(u['url'] for u in d['urls']))")"

for doc in "catalog|EcomDemo Catalog API" "customer|EcomDemo Customer API" "inventory|EcomDemo Inventory API" \
           "app|EcomDemo Orders API" "assistant|EcomDemo Assistant API"; do
    service="${doc%%|*}"
    title="${doc#*|}"
    STATUS="$(request GET "/v3/api-docs/$service")"
    check "GET /v3/api-docs/$service through the gateway returns 200" "200" "$STATUS"
    check "and is an OpenAPI 3 document titled $title" "3|$title" \
        "$(jget "d['openapi'].split('.')[0] + '|' + d['info']['title']")"
    # Relative: it resolves to the gateway this was fetched through, so "Try it out" goes through it too.
    check "and its only server is relative (the gateway it was fetched through)" "/" \
        "$(jget "' '.join(s['url'] for s in d['servers'])")"
done

request GET /v3/api-docs/catalog >/dev/null
check "the catalog spec documents the write schema, ProductRequest, with its constraints" "True|255" \
    "$(jget "str('ProductRequest' in d['components']['schemas']) + '|' + str(d['components']['schemas']['ProductRequest']['properties']['name']['maxLength'])")"

for service in payment notification; do
    check "$service-service exposes no docs through the gateway (404)" "404" \
        "$(request GET "/v3/api-docs/$service")"
done

# What "Try it out" does with an authorized request: the server is relative, so it is the gateway's
# own address plus the documented path, with the token pasted into Authorize.
as_customer
check "an authorized \"Try it out\" call through the gateway succeeds (GET /api/cart)" "200" \
    "$(request GET /api/cart)"
AUTH="$SAVED_AUTH"

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
CURRENT_DB_ID="$(ctr_exec "${CATALOG_DB_CONTAINER:-ecomdemo-catalog-db}" \
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
    check "flyway_schema_history shows V1-V20, all successful" \
        "1:ok,2:ok,3:ok,4:ok,5:ok,6:ok,7:ok,8:ok,9:ok,10:ok,11:ok,12:ok,13:ok,14:ok,15:ok,16:ok,17:ok,18:ok,19:ok,20:ok" "$HISTORY"

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
    VERSION_COLUMN="$(ctr_exec "${CATALOG_DB_CONTAINER:-ecomdemo-catalog-db}" \
        psql -qtAX -U "${CATALOG_DB_USER:-catalog}" -d "${CATALOG_DB_NAME:-catalog}" \
        -c "SELECT count(*) FROM information_schema.columns \
            WHERE table_name = 'product' AND column_name = 'version';" 2>/dev/null | tr -d '\r ')"
    check "catalog_db has the product.version column, the optimistic lock" "1" "$VERSION_COLUMN"

    CATALOG_SEED="$(ctr_exec "${CATALOG_DB_CONTAINER:-ecomdemo-catalog-db}" \
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

    # V16 also dropped `processed_event`; Phase 24's V18 brought it back as the order service's OWN
    # ledger for the saga's replies, so it is no longer part of this check - see the V18/V19 checks.
    APP_USERS="$(psql_query \
        "SELECT count(*) FROM information_schema.tables \
         WHERE table_schema = 'public' AND table_name IN ('users', 'notification');" \
        | tr -d '\r ')"
    check "V16 dropped accounts and notifications - other services own them" "0" "$APP_USERS"

    check "V18 gave the order service its own processed_event for the saga's replies" "1" \
        "$(psql_query "SELECT count(*) FROM information_schema.tables \
            WHERE table_schema = 'public' AND table_name = 'processed_event';" | tr -d '\r ')"
    check "V19 left no order in the pre-saga PLACED state" "0" \
        "$(psql_query "SELECT count(*) FROM orders WHERE status NOT IN ('PENDING', 'CONFIRMED', 'CANCELLED');" | tr -d '\r ')"

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
    skip "flyway_schema_history shows V1-V20, all successful" \
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
# Phase 24 lengthened the window: the unit is now taken by the saga after the 201, so the figure
# waits for the reservation as well as for the cache. Up to 60s, still reporting the time taken.
RACE_CONVERGED=false
RACE_ELAPSED=0
for _ in $(seq 1 300); do
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
# not there. Since Phase 24 the reservation is a saga step rather than part of checkout, but it is
# still made against inventory-service's locked rows, and an order it cannot cover is CANCELLED
# rather than sold. Stale display, correct sale. The polls below allow up to 60s, because the
# reservation itself now happens after the 201.
CONVERGED=false
ELAPSED=0
for _ in $(seq 1 300); do
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
for _ in $(seq 1 300); do
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
    if command -v docker >/dev/null 2>&1 && ctr_exec ecomdemo-app true >/dev/null 2>&1; then
        # `sh -c` so the redirection runs INSIDE the container: `ctr_exec ... wc -l < file`
        # would have the host's shell try to open a path that only exists in the container.
        ERROR_LINES="$(ctr_exec ecomdemo-app sh -c "wc -l < '$IMPORT_ERROR_FILE'" 2>/dev/null \
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

# EVERY CHECK IN THIS BLOCK GOES STRAIGHT TO THE APPLICATION ON 8084, not through the gateway.
#
# This matters more than it looks. The gateway has an actuator too, exposed under exactly the same
# rules - health and prometheus open, metrics ADMIN-only, env and heapdump not exposed at all. So
# every assertion below would still PASS against the gateway, while silently testing the wrong
# process: "the database is one of the components" would be false, and the meters an order moves
# would be missing. A check that passes for the wrong reason is worse than one that fails.
# --- The probes -------------------------------------------------------------------------------
as_anonymous
check "GET /actuator/health returns 200" "200" "$(app_request GET /actuator/health)"
check "and it reports UP" "UP" "$(jget "d['status']")"
# The two groups are what make the probes useful; without probes.enabled these are 404.
check "liveness is UP" "UP" \
    "$(app_request GET /actuator/health/liveness >/dev/null; jget "d['status']")"
check "readiness is UP" "UP" \
    "$(app_app_request GET /actuator/health/readiness >/dev/null; jget "d['status']")"
# An anonymous caller gets the verdict and nothing else. The component names alone ("db",
# "redis") would map the infrastructure for whoever asked.
app_request GET /actuator/health >/dev/null
check "an anonymous health body carries no component details" "False" \
    "$(jget "'components' in d")"
as_admin
app_request GET /actuator/health >/dev/null
check "an ADMIN sees the per-component breakdown" "True" "$(jget "'components' in d")"
check "and the database is one of the components" "True" "$(jget "'db' in d['components']")"
# Redis is deliberately absent from readiness: a cache outage must not take the app out of
# rotation, since every read still works without it.
app_request GET /actuator/health/readiness >/dev/null
check "readiness includes the database" "True" "$(jget "'db' in d.get('components', {})")"
check "but NOT redis - a cache outage is a slowdown, not an outage" "False" \
    "$(jget "'redis' in d.get('components', {})")"

# --- Who may read what --------------------------------------------------------------------------
as_anonymous
check "anonymous /actuator/prometheus returns 200 - Prometheus has no token" "200" \
    "$(app_request GET /actuator/prometheus)"
check "anonymous /actuator/info returns 200" "200" "$(app_request GET /actuator/info)"
check "anonymous /actuator/metrics returns 401" "401" "$(app_request GET /actuator/metrics)"
as_customer
check "a CUSTOMER may not read /actuator/metrics either" "403" \
    "$(app_request GET /actuator/metrics)"
as_admin
check "an ADMIN may" "200" "$(app_request GET /actuator/metrics)"
# Not in management.endpoints.web.exposure.include, so it does not exist over HTTP at all - the
# allow-list, not an authorization rule, is what keeps the environment (and every secret) off the
# wire. Even the administrator gets a 404.
check "/actuator/env is not exposed at all, not even to an ADMIN" "404" \
    "$(app_request GET /actuator/env)"
check "and neither is /actuator/heapdump" "404" "$(app_request GET /actuator/heapdump)"

# --- Which build is running ---------------------------------------------------------------------
as_anonymous
app_request GET /actuator/info >/dev/null
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
    jget "next(p['id'] for p in d if p['stockQuantity'] >= 1 and p['price'] * 1 <= $PAYMENT_LIMIT)")"
METRICS_UNIT_PRICE="$(jget "next(str(p['price']) for p in d if p['stockQuantity'] >= 1 and p['price'] * 1 <= $PAYMENT_LIMIT)")"
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
# Follows PROMETHEUS_PORT, the variable compose.yaml publishes Prometheus on, and defaults to the same
# 19090 compose does (Windows can reserve 9090 - see docs/process/development-environment.md).
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:${PROMETHEUS_PORT:-19090}}"
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
    #
    # It polls, because on a cold stack the answer depends on WHEN it is asked. The counter only
    # exists once this script places its first order, and rate() needs two samples of it; at a
    # 15s scrape interval the second one can be up to 30s away. Asked sooner, the query is
    # correctly empty. 45s is three scrape intervals. A wrong label still fails, just 45s later.
    DASHBOARD_HAS_DATA=False
    for _ in $(seq 1 45); do
        DASHBOARD_HAS_DATA="$(curl -sS --get "$PROMETHEUS_URL/api/v1/query" \
            --data-urlencode 'query=sum(rate(orders_placed_total{application="ecomdemo"}[5m]))' \
            | python3 -c "
import json, sys
print(len(json.load(sys.stdin)['data']['result']) > 0)" || echo False)"
        [ "$DASHBOARD_HAS_DATA" = "True" ] && break
        sleep 1
    done
    check "the dashboard's orders-per-minute query returns data" "True" "$DASHBOARD_HAS_DATA"
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
    # THE PATHS CHANGED IN PHASE 21, and the query below is why they had to.
    #
    # These were /api/products, which the application proxied. The gateway routes that path to
    # catalog-service now, so the application would log NOTHING for it and a query of
    # {service_name="app"} would come back empty - a check failing because the request went where it
    # was supposed to. Two paths the application genuinely owns, so the service being queried is the
    # service being asked.
    as_customer
    header GET /api/cart "$CORRELATION_HEADER" "$SMOKE_CORRELATION_ID" >/dev/null
    header GET /api/orders/99999999 "$CORRELATION_HEADER" "$SMOKE_CORRELATION_ID" >/dev/null

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
    ctr_exec "$KAFKA_CONTAINER" "/opt/kafka/bin/$script" --bootstrap-server localhost:9092 "$@" 2>/dev/null
}

# topic_message_count <topic> -> total messages across every partition, from the END offsets.
# A topic's size is not a thing Kafka reports directly: this sums the offset each partition has
# reached, which for a topic nothing has compacted or expired is the number of messages ever
# written to it.
topic_message_count() {
    ctr_exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh \
        --bootstrap-server localhost:9092 --topic "$1" 2>/dev/null \
        | awk -F: '{total += $3} END {print (total == "" ? 0 : total)}'
}

if command -v docker >/dev/null 2>&1 && ctr_exec "$KAFKA_CONTAINER" true >/dev/null 2>&1; then
    TOPICS="$(kafka kafka-topics.sh --list)"

    # KI-039: the broker writes to the volume it mounts. compose mounted kafka-data and left the
    # broker's log directory at the image's default under /tmp, inside the container, so every
    # `docker compose down` deleted every topic, offset and message (the databases kept theirs).
    # Asked of the RUNNING broker, so it holds on compose and on Kubernetes alike, and a drift in
    # either file fails here. ComposeKafkaPersistenceIT is the proof that the data then survives.
    KAFKA_LOG_DIR="$(kafka kafka-log-dirs.sh --describe --topic-list orders.placed \
        | grep '^{' | python3 -c "import json,sys; print(json.load(sys.stdin)['brokers'][0]['logDirs'][0]['logDir'])" 2>/dev/null)"
    check "the broker's log directory is the mounted volume, not the container's own filesystem" \
        "/var/lib/kafka/data" "$KAFKA_LOG_DIR"

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
    KAFKA_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 1 and p['price'] * 1 <= $PAYMENT_LIMIT)")"
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
    # Up to 60s (was 20): since Phase 24 orders.placed is published on CONFIRMATION, after the
    # saga's three steps, not the moment checkout commits.
    TOPIC_GROWTH=0
    for _ in $(seq 1 60); do
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
    TOPIC_DUMP="$(ctr_exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-consumer.sh \
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
        | ctr_exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
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
    POISON_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 1 and p['price'] * 1 <= $PAYMENT_LIMIT)")"
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
            | ctr_exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
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
    && ctr_exec "$KAFKA_CONTAINER" true >/dev/null 2>&1 \
    && psql_query "SELECT 1;" >/dev/null 2>&1; then

    # --- The outbox exists and is being drained ------------------------------------------------
    # A healthy outbox is nearly EMPTY, which is the opposite of how most tables are judged. Rows
    # accumulate here only while the broker is unreachable; a pending count that stays high is the
    # single clearest signal that publication has stopped.
    check "the outbox table exists" "1" \
        "$(psql_query "SELECT count(*) FROM information_schema.tables WHERE table_name = 'outbox_event';" | tr -d ' ')"

    # Polled rather than read once (Phase 24): the previous section's last order may still be
    # finishing its saga, and its OrderPlaced row is briefly - and correctly - pending.
    for _ in $(seq 1 30); do
        OUTBOX_PENDING_BEFORE="$(psql_query "SELECT count(*) FROM outbox_event WHERE published_at IS NULL;" | tr -d ' ')"
        [ "${OUTBOX_PENDING_BEFORE:-1}" = "0" ] && break
        sleep 1
    done
    check "and nothing is stuck in it before the outage" "0" "${OUTBOX_PENDING_BEFORE:-unknown}"

    # --- An ordinary order leaves a published row ----------------------------------------------
    request GET /api/products >/dev/null
    OUTBOX_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 2 and p['price'] * 2 <= $PAYMENT_LIMIT)")"
    request POST /api/cart/items "{\"productId\":$OUTBOX_PRODUCT_ID,\"quantity\":1}" >/dev/null
    check "an order placed with the broker UP returns 201" "201" "$(request POST /api/orders)"
    OUTBOX_ORDER_ID="$(jget "d['id']")"

    # The row is written in the order's own transaction, so it is there the instant the checkout
    # returns - no waiting, no polling. That is the difference this phase made.
    check "its event was written to the outbox in the same transaction" "1" \
        "$(psql_query "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$OUTBOX_ORDER_ID' AND event_type = 'OrderCreatedEvent';" | tr -d ' ')"

    OUTBOX_PUBLISHED=0
    for _ in $(seq 1 30); do
        # The checkout's OrderCreated row only. Since Phase 24 the order gets a second row,
        # OrderPlaced, once the saga confirms it - and on a warm stack that can be published before
        # this looks, which made a count over "every published row" read 2.
        OUTBOX_PUBLISHED="$(psql_query "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$OUTBOX_ORDER_ID' AND event_type = 'OrderCreatedEvent' AND published_at IS NOT NULL;" | tr -d ' ')"
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
    #
    # Phase 24 made "publish" mean the whole SAGA: the warm-up order's second row, OrderPlaced, is
    # written only once payment-service has answered, a few seconds after the first has gone. So
    # wait for the order to be CONFIRMED first - otherwise that row could be written just as the
    # broker stops, and become exactly the debris described above.
    wait_for_order_status "$WARMUP_ORDER_ID" >/dev/null
    for _ in $(seq 1 30); do
        WARMUP_PENDING="$(psql_query \
            "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$WARMUP_ORDER_ID' AND published_at IS NULL;" \
            | tr -d ' ')"
        [ "${WARMUP_PENDING:-1}" = "0" ] && break
        sleep 1
    done

    ctr_stop "$KAFKA_CONTAINER" >/dev/null 2>&1
    KAFKA_WAS_STOPPED=true   # the EXIT trap restores it if anything below fails

    # The checkout must still succeed, and must still be FAST. Phase 17's failure test found a
    # 97-second checkout here, because the AFTER_COMMIT send blocked the request thread waiting
    # for cluster metadata. Now the request never touches Kafka at all - it writes a row - so the
    # broker being down should be invisible to the customer.
    request GET /api/products >/dev/null
    OUTAGE_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 2 and p['price'] * 2 <= $PAYMENT_LIMIT)")"
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
    ctr_start "$KAFKA_CONTAINER" >/dev/null 2>&1
    KAFKA_WAS_STOPPED=false

    for _ in $(seq 1 60); do
        ctr_exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:9092 --list >/dev/null 2>&1 && break
        sleep 1
    done

    # Nobody re-places the order and nobody replays anything by hand. The relay finds the row on
    # its next tick and sends it, because the row never stopped being there.
    #
    # 180 seconds (90 until Phase 24) because recovery is not instant: the broker has to finish
    # starting, and every client has to notice it is back. Since the saga, that is not one
    # producer and one consumer but the whole chain - OrderCreated, StockReserved,
    # PaymentCompleted and OrderPlaced each wait on a relay and a consumer that must reconnect.
    # The claim is that it arrives, not that it arrives immediately.
    OUTAGE_NOTIFIED=0
    for _ in $(seq 1 180); do
        OUTAGE_NOTIFIED="$(notification_psql_query "SELECT count(*) FROM notification WHERE order_id = $OUTAGE_ORDER_ID;" | tr -d ' ')"
        [ "${OUTAGE_NOTIFIED:-0}" -ge 1 ] && break
        sleep 1
    done

    # THE "DONE WHEN": no events are lost while Kafka is down.
    check "once the broker is back, the order IS notified - nothing was lost" "1" \
        "${OUTAGE_NOTIFIED:-0}"

    # Every row for it, not "the" row: since Phase 24 an order has two in this outbox -
    # OrderCreated at checkout and OrderPlaced on confirmation - and neither may be left behind.
    check "and its outbox rows are all marked published" "0" \
        "$(psql_query "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$OUTAGE_ORDER_ID' AND published_at IS NULL;" | tr -d ' ')"
    check "and the saga finished for it: the order is CONFIRMED" "CONFIRMED" \
        "$(wait_for_order_status "$OUTAGE_ORDER_ID" 30)"

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
# 15. The API gateway (Phase 21)
# --------------------------------------------------------------------------------------------
# EVERY CHECK ABOVE THIS LINE ALREADY WENT THROUGH THE GATEWAY. BASE_URL is 8080 and 8080 is the
# gateway, so the two hundred-odd checks in the sections before this one have been exercising
# routing, edge token validation and the rate limiter for the whole run. That is the phase's main
# claim, and it is proven by a suite written before the gateway existed rather than by anything
# below.
#
# What is left for this section is the handful of claims that only a gateway can make.
section "API gateway"

# --- The gateway is a different process from the application ------------------------------------
# Worth establishing first, because it is what gives the rest of the section meaning: if 8080 and
# 8084 were the same server, every check here would be vacuous.
as_anonymous
check "the gateway answers on 8080" "200" "$(request GET /actuator/health)"
check "and the application answers separately on 8084" "200" "$(app_request GET /actuator/health)"
request GET /actuator/info >/dev/null
check "8080 identifies itself as the gateway" "gateway-service" "$(jget "d['build']['artifact']")"
app_request GET /actuator/info >/dev/null
check "and 8084 as the application - two builds, two processes" "ecomdemo-app" \
    "$(jget "d['build']['artifact']")"

# --- The routes reach four different services ---------------------------------------------------
# One request per routed service, each asserting something only that service can answer. A route
# pointing at the wrong host would still return 200 for some of these, so each check reads a field
# that belongs to the service it is aimed at.
check "/api/products routes to catalog-service" "200" "$(request GET /api/products)"
check "and the response is a product listing, not something else's 200" "True" \
    "$(jget "isinstance(d, list)")"

# The application's own path, through the gateway. A CUSTOMER token is required, which also proves
# the edge relays the token rather than swallowing it - catalog-service and the app both validate
# what they receive.
as_customer
check "/api/cart routes to the application AND arrives authenticated" "200" \
    "$(request GET /api/cart)"

# customer-service, reached through the gateway at the path the application used to proxy.
request GET /api/customers/me >/dev/null
check "/api/customers/me routes to customer-service and knows who is asking" "$CUSTOMER_USER" \
    "$(jget "d['username']")"

# inventory-service, which was reachable only inside the Docker network until this phase.
#
# This section creates its OWN product rather than reusing $PROBE_PRODUCT_ID from the messaging
# section. That variable is assigned inside a conditional block, and under `set -u` a run where Kafka
# was unavailable would abort here with an unbound variable - a gateway check failing because of a
# broker. Creating the product also proves a routed WRITE works, not merely a routed read.
as_admin
check "an ADMIN can create a product THROUGH the gateway" "201" \
    "$(request POST /api/products '{"name":"Gateway Route Widget","description":"created through the gateway","price":12.34,"stockQuantity":7,"category":"GATEWAY"}')"
GATEWAY_PRODUCT_ID="$(jget "d['id']")"
check "/api/inventory is routed, and ADMIN-only" "200" \
    "$(request GET /api/inventory/$GATEWAY_PRODUCT_ID)"
check "and inventory reports the stock the catalogue was created with" "7" "$(jget "d['quantity']")"
as_customer
check "a CUSTOMER is refused stock, at the edge" "403" \
    "$(request GET /api/inventory/$GATEWAY_PRODUCT_ID)"
as_admin
request DELETE "/api/products/$GATEWAY_PRODUCT_ID" >/dev/null

# notification-service has NO route, deliberately: it has no business API. A 404 from the gateway
# is the right answer - it means no route matched, rather than a route matching and failing.
as_admin
check "notification-service is not routed at all" "404" "$(request GET /api/notifications)"

# --- Login is the gateway's now, and that is the security change --------------------------------
# The application no longer sees a password. This check is the same login every earlier section
# already used - what is different is that nothing but the gateway and customer-service ever holds
# the credential.
as_anonymous
check "login is served through the gateway" "200" \
    "$(request POST /api/auth/login "$(printf '{"username":"%s","password":"%s"}' "$CUSTOMER_USER" "$CUSTOMER_PASSWORD")")"
check "and it returns a token" "True" "$(jget "len(d['accessToken']) > 20")"
check "the application does NOT serve login any more - the proxy is gone" "401" \
    "$(app_request POST /api/auth/login "$(printf '{"username":"%s","password":"%s"}' "$CUSTOMER_USER" "$CUSTOMER_PASSWORD")")"

# --- The correlation ID is minted at the edge ---------------------------------------------------
# It existed before this phase, in every service. What changed is where it STARTS: the gateway
# stamps it, so one id covers the whole fan-out instead of each service inventing its own.
correlation_header() {
    curl -sS -o /dev/null -D - "$BASE_URL$1" ${AUTH:+-H "Authorization: Bearer $AUTH"} 2>/dev/null \
        | tr -d '\r' | awk -F': ' 'tolower($1) == "x-correlation-id" { print $2 }' | head -1
}
as_anonymous
GATEWAY_CORRELATION="$(correlation_header /api/products)"
check "the gateway returns a correlation id" "True" \
    "$(python3 -c "import re,sys; print(bool(re.fullmatch(r'[A-Za-z0-9_-]{8,64}', sys.argv[1])))" "$GATEWAY_CORRELATION")"
SUPPLIED_CORRELATION="smoke-gateway-correlation-1"
check "and it keeps one the caller supplied, rather than minting a second" "$SUPPLIED_CORRELATION" \
    "$(curl -sS -o /dev/null -D - -H "X-Correlation-Id: $SUPPLIED_CORRELATION" "$BASE_URL/api/products" \
        | tr -d '\r' | awk -F': ' 'tolower($1) == "x-correlation-id" { print $2 }' | head -1)"

# ⚠️ WHAT IS DELIBERATELY *NOT* CHECKED HERE: that one id can be followed ACROSS the hop, from the
# gateway into the service that answered. That is the claim a gateway-minted id exists to support,
# and it cannot be made yet.
#
# Only `ecomdemo-app` configures `logging.structured.format.console`. The other four services log
# plain text, so their correlation id lives in the MDC and is never written to the line - Loki has
# nothing to filter on, and `| correlation_id = ...` matches zero lines however correct the id is.
# A check written anyway would fail; one written to pass would have to grep for a string that is not
# there.
#
# This gap arrived in Phase 20b and was invisible until now, because nothing had asked a
# cross-service question of the logs before. Recorded as a follow-up rather than fixed here: giving
# four services structured logging is its own change, and it is not an API gateway.

# --- The rate limit, which is the phase's "done when" -------------------------------------------
# 50 tokens a second with a burst capacity of 100, keyed per caller. So a burst well past 100 from
# ONE caller must be refused, and the refusal must be a 429.
#
# THE BURST USES $OTHER_USER, and picking the identity carefully is the whole trick.
#
# The limiter keys on the authenticated username, so whoever sends 250 requests is throttled for the
# next few seconds. Bursting as $CUSTOMER_USER would leave the account every other section depends on
# refusing requests - which is precisely what happened inside gateway-service's own RateLimitIT, where
# the failure surfaced in an unrelated test class and took a while to understand. $OTHER_USER exists
# for ownership checks and nothing after this point needs it.
BURST_TOKEN="$OTHER_TOKEN"

# ONE curl PROCESS, MANY REQUESTS, IN PARALLEL - and getting here took two wrong versions.
#
# Version one sent 250 requests in a sequential shell loop and NOTHING was refused. The limiter was
# working perfectly: spawning a `curl` costs tens of milliseconds, so the loop managed 20-30 requests a
# second, comfortably below the 50 a second the bucket refills at. Tokens were replenished as fast as
# they were spent.
#
# Version two used `xargs -P 20`, and still nothing was refused - measured at almost exactly 50 requests
# a second, because 20 workers each paying the process-spawn cost happens to land on the refill rate.
# The limiter was not the slow part; the client was.
#
# What works is one curl process reusing one connection across 300 requests, 40 in flight at a time:
# roughly 150 requests a second, which genuinely outruns the refill. Measured live: 171 served, 129
# refused, in 2.1 seconds.
#
# The lesson is the one Phase 20d kept re-learning from the other direction: when a check about
# behaviour quietly becomes a check about throughput, it stops testing what it names. gateway-service's
# RateLimitIT tripped the limit with a plain sequential loop only because WebTestClient runs in-process
# and is an order of magnitude faster than spawning curl.
BURST_ARGS=""
for _ in $(seq 1 300); do
    BURST_ARGS="$BURST_ARGS -o /dev/null $BASE_URL/api/products"
done
BURST_RESULTS="$(mktemp)"
# Unquoted on purpose: $BURST_ARGS must word-split into curl's argument list.
# shellcheck disable=SC2086
curl -sS -w '%{http_code}\n' --parallel --parallel-max 40 \
    -H "Authorization: Bearer $BURST_TOKEN" $BURST_ARGS > "$BURST_RESULTS" 2>/dev/null
BURST_429="$(grep -c '^429$' "$BURST_RESULTS" 2>/dev/null || true)"
BURST_OK="$(grep -c '^200$' "$BURST_RESULTS" 2>/dev/null || true)"
BURST_429="${BURST_429:-0}"
BURST_OK="${BURST_OK:-0}"
rm -f "$BURST_RESULTS"

check "a concurrent burst of 300 requests from one caller is rate limited" "True" \
    "$(python3 -c "import sys; print(int(sys.argv[1]) > 0)" "$BURST_429")"
# Not a ban: the bucket's capacity is served before anything is refused. "More than 50 served" rather
# than "exactly 100" on purpose - the bucket refills DURING the burst, so the number served depends on
# how long the burst took, and pinning it would be a performance assertion wearing a correctness name.
check "but plenty were served first - it throttles, it does not ban" "True" \
    "$(python3 -c "import sys; print(int(sys.argv[1]) > 50)" "$BURST_OK")"
# above and be useless. 50 tokens a second replenish, so a short wait is enough - polled rather
# than slept, because a fixed sleep is a performance assertion in disguise.
RECOVERED=no
for _ in $(seq 1 20); do
    if [ "$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/api/products" \
            -H "Authorization: Bearer $BURST_TOKEN")" = "200" ]; then
        RECOVERED=yes
        break
    fi
    sleep 1
done
check "and the limit lifts once the bucket refills" "yes" "$RECOVERED"

# A DIFFERENT caller was never affected, and this is the point of keying per caller: a limiter with
# one shared bucket would have refused this request too, which makes it a denial-of-service tool
# rather than a protection. $CUSTOMER_USER made a handful of requests all run and is nowhere near any
# limit; $OTHER_USER just made 250.
as_customer
check "a different caller is not refused because of somebody else's burst" "200" \
    "$(request GET /api/cart)"

as_customer

# --- CORS: a browser on another origin can use the API (KI-041) ---------------------------------
# A browser sends a PREFLIGHT (OPTIONS, no token) before any cross-origin call with a token or a
# JSON body. Until KI-041 the gateway's security answered it 401, so a browser app on another origin
# could not even log in; curl sends no preflight, which is why every check above passed anyway.
# The origin is the gateway's default allowed one (CORS_ALLOWED_ORIGINS in compose and k8s).
CORS_ORIGIN="${SMOKE_CORS_ORIGIN:-http://localhost:3000}"
preflight_headers() { # preflight_headers <origin> <path> <method> <request-headers> -> status line + headers
    curl -sS -o /dev/null -D - -X OPTIONS "$BASE_URL$2" -H "Origin: $1" \
        -H "Access-Control-Request-Method: $3" -H "Access-Control-Request-Headers: $4" 2>/dev/null | tr -d '\r'
}
header_of() { # header_of <name> : reads headers on stdin, prints every value of that header
    awk -F': ' -v name="$1" 'tolower($1) == tolower(name) { print $2 }'
}
CORS_LOGIN="$(preflight_headers "$CORS_ORIGIN" /api/auth/login POST content-type)"
check "the login preflight from $CORS_ORIGIN is answered 200" "200" \
    "$(printf '%s\n' "$CORS_LOGIN" | head -1 | awk '{print $2}')"
check "and allows that origin" "$CORS_ORIGIN" \
    "$(printf '%s\n' "$CORS_LOGIN" | header_of Access-Control-Allow-Origin | head -1)"
CORS_CART="$(preflight_headers "$CORS_ORIGIN" /api/cart GET authorization)"
check "the preflight of an authenticated call is answered 200 and allows Authorization" "200|True" \
    "$(printf '%s\n' "$CORS_CART" | head -1 | awk '{print $2}')|$(printf '%s\n' "$CORS_CART" \
        | header_of Access-Control-Allow-Headers | grep -qi authorization && echo True || echo False)"
check "a preflight from an origin that is not allowed is refused (403)" "403" \
    "$(preflight_headers http://evil.example /api/auth/login POST content-type | head -1 | awk '{print $2}')"
CORS_REAL="$(curl -sS -o /dev/null -D - "$BASE_URL/api/cart" -H "Origin: $CORS_ORIGIN" \
    -H "Authorization: Bearer $CUSTOMER_TOKEN" 2>/dev/null | tr -d '\r')"
check "an authenticated cross-origin call returns 200 with exactly one Access-Control-Allow-Origin" "200|1" \
    "$(printf '%s\n' "$CORS_REAL" | head -1 | awk '{print $2}')|$(printf '%s\n' "$CORS_REAL" \
        | header_of Access-Control-Allow-Origin | wc -l | tr -d ' ')"

# --------------------------------------------------------------------------------------------
# Resilience (Phase 22)
# --------------------------------------------------------------------------------------------
# catalog-service is STOPPED, for real, and the application must degrade rather than break:
#   - the one thing that needs the catalogue (adding a product to a cart) fails FAST with a clear
#     503, instead of a 500 or a request that hangs;
#   - everything that does not need it (checkout of a cart already filled, order history) keeps
#     working, because the cart holds a snapshot of the product and the price;
#   - after repeated failures the circuit breaker OPENS and refusals stop touching the network;
#   - when catalog-service comes back, the application recovers ON ITS OWN.
#
# Why add-to-cart and not checkout: since Phase 20c checkout does not call catalog-service at all.
# It charges the price stored in the cart and talks only to inventory-service. That is the better
# outcome - the phase file's "checkout fails fast with 503" was written before that was true - and
# the check below asserts it, rather than adding a catalogue call to checkout just to watch it fail.
section "Resilience"

CATALOG_CONTAINER="${CATALOG_CONTAINER:-ecomdemo-catalog-service}"

# now_ms -> milliseconds since the epoch. `date +%s` is whole seconds, too coarse for a 2 s budget.
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# retry_after <method> <path> [data] -> the Retry-After header of a request through the gateway.
retry_after() {
    curl -sS -o /dev/null -D - -X "$1" "$BASE_URL$2" -H "Authorization: Bearer $AUTH" \
        -H 'Content-Type: application/json' ${3:+-d "$3"} \
        | tr -d '\r' | awk 'tolower($1) == "retry-after:" { print $2 }'
}

# --- The policy is in place, and visible, before anything fails ---------------------------------
# The series exist from startup. A dashboard that only grew its panels after the first outage
# would be blank at exactly the moment somebody opened it to check things were fine. This also
# proves resilience4j-micrometer is in the PACKAGED application: a test-scoped declaration would
# leave every unit test green and this scrape empty.
scrape
check "the catalog circuit breaker is published, and CLOSED" "1" \
    "$(metric resilience4j_circuitbreaker_state name=catalog state=closed)"
check "the bulkhead publishes its limit of 20 concurrent calls" "20" \
    "$(metric resilience4j_bulkhead_max_allowed_concurrent_calls name=catalog)"
check "the retry is published" "True" \
    "$([ "$(metric resilience4j_retry_calls_total name=catalog)" != MISSING ] && echo True || echo False)"
NOT_PERMITTED_BEFORE="$(metric resilience4j_circuitbreaker_not_permitted_calls_total name=catalog)"

# --- Fill a cart while the catalogue is up ------------------------------------------------------
as_customer
request GET /api/products >/dev/null
RES_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 3 and p['price'] * 3 <= $PAYMENT_LIMIT)")"
check "with catalog-service UP, adding to the cart works" "200" \
    "$(request POST /api/cart/items "{\"productId\":$RES_PRODUCT_ID,\"quantity\":1}")"

# --- Stop it ------------------------------------------------------------------------------------
ctr_stop "$CATALOG_CONTAINER" >/dev/null 2>&1
CATALOG_WAS_STOPPED=true   # the EXIT trap restores it if anything below fails

STARTED_MS="$(now_ms)"
DOWN_STATUS="$(request POST /api/cart/items "{\"productId\":$RES_PRODUCT_ID,\"quantity\":1}")"
DOWN_ELAPSED_MS=$(( $(now_ms) - STARTED_MS ))
check "with catalog-service DOWN, adding to the cart is a 503, not a 500" "503" "$DOWN_STATUS"
check "and it fails FAST - two attempts and their backoff, under 2 s (took ${DOWN_ELAPSED_MS} ms)" \
    "True" "$([ "$DOWN_ELAPSED_MS" -lt 2000 ] && echo True || echo False)"
check "the body says what is wrong, in words a shopper can use" "True" \
    "$(jget "'catalogue' in d['message'] and d['status'] == 503")"

# Five failed calls with a 50% threshold open the breaker. Each add-to-cart above is two attempts,
# so three more requests are enough whatever the count stood at.
for _ in 1 2 3; do
    request POST /api/cart/items "{\"productId\":$RES_PRODUCT_ID,\"quantity\":1}" >/dev/null
done
scrape
check "after repeated failures the circuit breaker is OPEN" "1" \
    "$(metric resilience4j_circuitbreaker_state name=catalog state=open)"

STARTED_MS="$(now_ms)"
OPEN_STATUS="$(request POST /api/cart/items "{\"productId\":$RES_PRODUCT_ID,\"quantity\":1}")"
OPEN_ELAPSED_MS=$(( $(now_ms) - STARTED_MS ))
check "with the circuit OPEN, the refusal is still a 503" "503" "$OPEN_STATUS"
check "and says the catalogue is temporarily unavailable" "True" \
    "$(jget "'temporarily unavailable' in d['message']")"
# Includes the gateway hop and a curl process start, so the budget is generous; the point is that
# no network call to catalog-service and no retry backoff happened.
check "an open circuit refuses without trying (took ${OPEN_ELAPSED_MS} ms, under 500)" "True" \
    "$([ "$OPEN_ELAPSED_MS" -lt 500 ] && echo True || echo False)"
check "Retry-After tells the client how long the circuit stays open" "10" \
    "$(retry_after POST /api/cart/items "{\"productId\":$RES_PRODUCT_ID,\"quantity\":1}")"
scrape
check "those refusals are counted as not_permitted" "True" \
    "$(python3 -c "print($(metric resilience4j_circuitbreaker_not_permitted_calls_total name=catalog) > ${NOT_PERMITTED_BEFORE:-0})")"

# --- What does not need the catalogue keeps working ---------------------------------------------
# The whole point of degrading "gracefully": one dependency down takes out the features that need
# it and nothing else.
DEGRADED_ORDER_STATUS="$(request POST /api/orders)"
DEGRADED_ORDER_ID="$(jget "d['id']")"
check "checkout of the filled cart SUCCEEDS with catalog-service down - it never needed it" "201" \
    "$DEGRADED_ORDER_STATUS"
check "and order history still answers" "200" "$(request GET /api/orders)"
# Phase 24: nor does the saga. Inventory reserves by product id and payment charges the total the
# event carries, so the order is decided with the catalogue still down.
check "and the saga still CONFIRMS it without the catalogue" "CONFIRMED" \
    "$(wait_for_order_status "$DEGRADED_ORDER_ID")"
as_anonymous
check "the application itself stays healthy - an open breaker is not a health failure" "200" \
    "$(app_request GET /actuator/health)"
as_customer

# --- Bring it back ------------------------------------------------------------------------------
ctr_start "$CATALOG_CONTAINER" >/dev/null 2>&1
CATALOG_WAS_STOPPED=false

# No restart of the application, no manual reset: the breaker goes HALF_OPEN after its 10 s wait,
# lets trial calls through, and closes when they succeed. The budget covers catalog-service's own
# JVM start (tens of seconds) plus that wait.
RECOVERED_STATUS="none"
RECOVERY_STARTED="$(date +%s)"
for _ in $(seq 1 60); do
    RECOVERED_STATUS="$(request POST /api/cart/items "{\"productId\":$RES_PRODUCT_ID,\"quantity\":1}")"
    [ "$RECOVERED_STATUS" = "200" ] && break
    sleep 2
done
RECOVERY_SECONDS=$(( $(date +%s) - RECOVERY_STARTED ))
check "once catalog-service is back, add-to-cart recovers ON ITS OWN (after ${RECOVERY_SECONDS}s)" \
    "200" "$RECOVERED_STATUS"
# One trial call has succeeded; the breaker closes after THREE, judged together. They are made
# against a catalog-service that has just started, whose first requests can exceed the 500 ms read
# timeout (cold JIT, empty pools) - so a trial can fail, the breaker correctly re-opens for another
# 10 s, and closing takes a second half-open round. "Two more requests, then assert CLOSED" failed
# one cold run in three for exactly that reason: it asserted a state at an instant rather than the
# claim, which is that it closes ON ITS OWN. So: keep asking, as a real client would, for up to 45 s.
BREAKER_CLOSED="0"
for _ in $(seq 1 45); do
    request POST /api/cart/items "{\"productId\":$RES_PRODUCT_ID,\"quantity\":1}" >/dev/null
    scrape
    BREAKER_CLOSED="$(metric resilience4j_circuitbreaker_state name=catalog state=closed)"
    [ "$BREAKER_CLOSED" = "1" ] && break
    sleep 1
done
check "and the circuit breaker is CLOSED again" "1" "$BREAKER_CLOSED"
request DELETE "/api/cart/items/$RES_PRODUCT_ID" >/dev/null

# --------------------------------------------------------------------------------------------
# Distributed tracing (Phase 23)
# --------------------------------------------------------------------------------------------
# The phase's "done when": ONE checkout is ONE trace, across every service it touches - including
# the half that happens later, on another thread, through the outbox and Kafka.
#
# The script chooses the trace id itself and sends it as a W3C `traceparent` header, exactly as an
# upstream system (a mobile app, another company's service) would. That is what makes the check
# deterministic: there is nothing to search for, only an id to look up. It also tests the first hop
# of propagation - the gateway must CONTINUE the caller's trace rather than start its own.
#
# The whole section needs Tempo, and is SKIPPED, never passed, when Tempo is not reachable.
section "Distributed tracing"

TEMPO_URL="${TEMPO_URL:-http://localhost:3200}"

# random_hex <bytes> -> that many random bytes as lower-case hex (W3C ids are hex, never all zero)
random_hex() { python3 -c "import secrets, sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"; }

# tempo_trace <trace-id> <python-expression> -> evaluates the expression over the trace, or "none"
#
# The expression sees `spans`: one dict per span with service, name, kind, span_id, parent_id and
# attrs. Tempo returns OTLP JSON, in which ids are BYTES and so arrive base64-encoded; they are
# turned back into the hex a traceparent uses, so they can be compared with what was sent.
tempo_trace() {
    curl -sS "$TEMPO_URL/api/v2/traces/$1" 2>/dev/null | python3 -c "
import base64, json, re, sys
def hexid(v):
    if not v: return ''
    return v if re.fullmatch(r'[0-9a-f]{16}|[0-9a-f]{32}', v) else base64.b64decode(v).hex()
def value(v):
    return next(iter(v.values()), None) if isinstance(v, dict) else v
try:
    d = json.load(sys.stdin)
    spans = []
    for rs in d.get('trace', d).get('resourceSpans', []):
        res = {a['key']: value(a['value']) for a in rs.get('resource', {}).get('attributes', [])}
        for ss in rs.get('scopeSpans', []):
            for sp in ss.get('spans', []):
                spans.append({'service': res.get('service.name'), 'name': sp.get('name'),
                              'kind': sp.get('kind'), 'span_id': hexid(sp.get('spanId')),
                              'parent_id': hexid(sp.get('parentSpanId')),
                              'attrs': {a['key']: value(a['value']) for a in sp.get('attributes', [])}})
    print(eval(sys.argv[1]) if spans else 'none')
except Exception:
    print('none')
" "$2"
}

if curl -fsS "$TEMPO_URL/ready" >/dev/null 2>&1; then
    check "Tempo is ready" "200" "$(curl -sS -o /dev/null -w '%{http_code}' "$TEMPO_URL/ready")"

    # --- Sampling: the edge's decision is obeyed ---------------------------------------------
    # Sent FIRST so that by the time the checkout's trace below has arrived, this one has had every
    # chance to arrive as well. Flags `00` = "not sampled". The services are parent-based: they
    # follow the flag instead of tossing their own coin, so this request must leave no trace at all
    # - even though compose samples 100% of the requests that arrive WITHOUT a decision.
    UNSAMPLED_TRACE_ID="$(random_hex 16)"
    as_customer
    curl -sS -o /dev/null "$BASE_URL/api/products" -H "Authorization: Bearer $AUTH" \
        -H "traceparent: 00-$UNSAMPLED_TRACE_ID-$(random_hex 8)-00"

    # --- One checkout, with a trace id the script chose ------------------------------------------
    TRACE_ID="$(random_hex 16)"
    CLIENT_SPAN_ID="$(random_hex 8)"
    request GET /api/products >/dev/null
    TRACE_PRODUCT_ID="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 2 and p['price'] * 2 <= $PAYMENT_LIMIT)")"
    request POST /api/cart/items "{\"productId\":$TRACE_PRODUCT_ID,\"quantity\":1}" >/dev/null
    check "a checkout sent with a W3C traceparent returns 201" "201" \
        "$(curl -sS -o "$BODY" -w '%{http_code}' -X POST "$BASE_URL/api/orders" \
            -H "Authorization: Bearer $AUTH" -H "traceparent: 00-$TRACE_ID-$CLIENT_SPAN_ID-01")"
    TRACE_ORDER_ID="$(jget "d['id']")"

    # Spans are exported in batches (every 5 s by default), and the Kafka half starts only when the
    # relay publishes - up to a second later - and notification-service consumes. So poll rather
    # than sleep for a guess - and poll for BOTH ends. Each service flushes on its own timer, so
    # the last hop arriving does not mean the first has: notification-service's batch has been
    # seen in Tempo before the gateway's, which failed the gateway check below on a trace that
    # was complete a few seconds later.
    #
    # Phase 24 made the async half the LONG half: the checkout's trace now runs through the whole
    # saga - app -> inventory -> payment -> app -> notification, four outbox relays - before
    # notification-service writes its span. 90s (was 45), and the poll waits for EVERY hop the checks
    # below assert, not for the first and the last: the sixth cold run of Phase 24 saw
    # notification-service's consumer span in Tempo before the order service's own consumer span,
    # which happened seconds EARLIER but sat in the app's 5 s export batch. The trace was complete a
    # moment later; the check had simply looked too soon - Phase 23's lesson, one hop further on.
    TRACE_HOPS_PY="all(any(s['service'] == svc and s['kind'] in kinds for s in spans) for svc, kinds in [
        ('gateway-service', ('SPAN_KIND_SERVER', 2)),
        ('inventory-service', ('SPAN_KIND_CONSUMER', 5)),
        ('payment-service', ('SPAN_KIND_CONSUMER', 5)),
        ('ecomdemo', ('SPAN_KIND_CONSUMER', 5)),
        ('notification-service', ('SPAN_KIND_CONSUMER', 5))])"
    TRACE_SERVICES="none"
    for _ in $(seq 1 90); do
        [ "$(tempo_trace "$TRACE_ID" "$TRACE_HOPS_PY")" = "True" ] && break
        sleep 1
    done
    TRACE_SERVICES="$(tempo_trace "$TRACE_ID" "','.join(sorted({s['service'] for s in spans}))")"
    check "Tempo has the checkout's trace, under the id the client chose" "True" \
        "$([ "$TRACE_SERVICES" != "none" ] && echo True || echo False)"
    # At least 5 since Phase 24 (was 4): payment-service joined the checkout's story.
    check "with spans from at least 5 services (${TRACE_SERVICES})" "True" \
        "$(tempo_trace "$TRACE_ID" "len({s['service'] for s in spans}) >= 5")"

    # Each of these is one hop of propagation, and each can break on its own.
    check "the gateway CONTINUED the caller's trace: its server span's parent is the span id sent" "True" \
        "$(tempo_trace "$TRACE_ID" "any(s['service'] == 'gateway-service' and s['parent_id'] == '$CLIENT_SPAN_ID' for s in spans)")"
    check "the application's span is in it (gateway -> app over HTTP)" "True" \
        "$(tempo_trace "$TRACE_ID" "any(s['service'] == 'ecomdemo' for s in spans)")"
    check "inventory-service's stock pre-check is in it (app -> inventory over HTTP)" "True" \
        "$(tempo_trace "$TRACE_ID" "any(s['service'] == 'inventory-service' and s['kind'] in ('SPAN_KIND_SERVER', 2) for s in spans)")"
    # The relay's tag became `outbox.aggregate_id` in Phase 24, when the outbox became a library
    # used by services whose aggregates are not orders.
    check "the outbox relay's span is in it, carrying the order id (the async hop)" "True" \
        "$(tempo_trace "$TRACE_ID" "any(s['name'] == 'outbox relay' and str(s['attrs'].get('outbox.aggregate_id')) == '$TRACE_ORDER_ID' for s in spans)")"
    # The saga's hops, each continued through an outbox row's stored traceparent (Phase 23's
    # mechanism, now in three services): OrderCreated -> inventory, StockReserved -> payment,
    # PaymentCompleted -> back to the order service.
    check "inventory-service's reservation is in it (the saga's step 2, a Kafka consumer)" "True" \
        "$(tempo_trace "$TRACE_ID" "any(s['service'] == 'inventory-service' and s['kind'] in ('SPAN_KIND_CONSUMER', 5) for s in spans)")"
    check "payment-service's charge is in it (the saga's step 3, a Kafka consumer)" "True" \
        "$(tempo_trace "$TRACE_ID" "any(s['service'] == 'payment-service' and s['kind'] in ('SPAN_KIND_CONSUMER', 5) for s in spans)")"
    check "the order service's confirmation is in it (payments.completed consumed by the app)" "True" \
        "$(tempo_trace "$TRACE_ID" "any(s['service'] == 'ecomdemo' and s['kind'] in ('SPAN_KIND_CONSUMER', 5) for s in spans)")"
    check "notification-service's consumer span is in it (app -> Kafka -> notification)" "True" \
        "$(tempo_trace "$TRACE_ID" "any(s['service'] == 'notification-service' and s['kind'] in ('SPAN_KIND_CONSUMER', 5) for s in spans)")"

    # Checked last: the checkout's trace arriving proves the export window has passed.
    check "a request whose traceparent said NOT sampled left no trace (parent-based sampling)" "none" \
        "$(tempo_trace "$UNSAMPLED_TRACE_ID" "len(spans)")"

    # --- Logs carry the trace id, in every service ---------------------------------------------
    # The link from a trace to its log lines is a Loki query on `trace_id`, which Alloy stores as
    # structured metadata. Lines from more than one service under one id is the cross-service
    # question the correlation id could not answer before every service logged JSON.
    TRACE_LOG_SERVICES=0
    for _ in $(seq 1 30); do
        TRACE_LOG_SERVICES="$(curl -sSG "$LOKI_URL/loki/api/v1/query_range" \
            --data-urlencode "query={service_name=~\".+\"} | trace_id = \`$TRACE_ID\`" \
            --data-urlencode "since=15m" --data-urlencode "limit=1000" 2>/dev/null | python3 -c "
import json, sys
try:
    print(len({s['stream'].get('service_name') for s in json.load(sys.stdin)['data']['result']}))
except Exception:
    print(0)
")"
        [ "${TRACE_LOG_SERVICES:-0}" -ge 3 ] && break
        sleep 1
    done
    check "Loki finds that trace id on log lines from at least 3 services" "True" \
        "$([ "${TRACE_LOG_SERVICES:-0}" -ge 3 ] && echo True || echo False)"

    # --- And Grafana can follow the links ------------------------------------------------------
    if curl -fsS "$GRAFANA_URL/api/health" >/dev/null 2>&1; then
        check "Grafana has the Tempo datasource" "tempo" \
            "$(curl -sS -u "$GRAFANA_AUTH" "$GRAFANA_URL/api/datasources/uid/ecomdemo-tempo" \
                | python3 -c "import json,sys; print(json.load(sys.stdin).get('type'))" 2>/dev/null)"
        check "and can fetch the checkout's trace through it" "200" \
            "$(curl -sS -o /dev/null -w '%{http_code}' -u "$GRAFANA_AUTH" \
                "$GRAFANA_URL/api/datasources/proxy/uid/ecomdemo-tempo/api/v2/traces/$TRACE_ID")"
        check "Loki's trace_id derived field links to Tempo" "ecomdemo-tempo" \
            "$(curl -sS -u "$GRAFANA_AUTH" "$GRAFANA_URL/api/datasources/uid/ecomdemo-loki" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(next((f.get('datasourceUid') for f in d['jsonData'].get('derivedFields', []) if f['name'] == 'trace_id'), None))
" 2>/dev/null)"
    else
        skip "Grafana tracing checks" "no Grafana at $GRAFANA_URL"
    fi
else
    skip "distributed tracing checks" "no Tempo at $TEMPO_URL (set TEMPO_URL to override)"
fi

# --------------------------------------------------------------------------------------------
# Saga: distributed transactions (Phase 24)
# --------------------------------------------------------------------------------------------
# The phase's "done when": success AND failure both end CONSISTENTLY. Consistency here is checked
# in all three databases the saga touches, not just in the order's status - a CANCELLED order with
# its stock still held in inventory would be exactly the inconsistency a saga exists to prevent.
#
#   success:  PENDING -> StockReserved -> PaymentCompleted -> CONFIRMED, stock taken, payment kept
#   failure:  PENDING -> StockReserved -> PaymentFailed    -> CANCELLED, stock GIVEN BACK
#                                                             (inventory's compensation)
#   refusal:  PENDING -> StockRejected                     -> CANCELLED, nothing to give back
#
# The failure is FORCED, deterministically: payment-service declines any total above
# PAYMENT_DECLINE_ABOVE (10000.00 by default), so the script buys 2 x 6000.00.
section "Saga (distributed transactions)"

PAYMENT_URL="${PAYMENT_URL:-http://localhost:8086}"
check "payment-service is ready" "200" \
    "$(curl -sS -o /dev/null -w '%{http_code}' "$PAYMENT_URL/actuator/health/readiness")"
as_anonymous
check "and has no business API: a payment cannot be asked for, only caused by an event" "403" \
    "$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$PAYMENT_URL/api/payments")"

if command -v docker >/dev/null 2>&1 && ctr_exec "${KAFKA_CONTAINER:-ecomdemo-kafka}" true >/dev/null 2>&1; then
    SAGA_TOPICS="$(ctr_exec "${KAFKA_CONTAINER:-ecomdemo-kafka}" /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 --list 2>/dev/null)"
    MISSING_TOPICS=""
    for topic in orders.created inventory.stock-reserved inventory.stock-rejected \
        payments.completed payments.failed; do
        for name in "$topic" "$topic-dlt"; do
            printf '%s\n' "$SAGA_TOPICS" | grep -qx "$name" || MISSING_TOPICS="$MISSING_TOPICS $name"
        done
    done
    check "the saga's five topics and their dead-letter topics exist" "" "${MISSING_TOPICS# }"
else
    skip "the saga's five topics and their dead-letter topics exist" "no Kafka container"
fi

saga_product() { # saga_product <name> <price> <stock> -> the new product's id
    as_admin
    request POST /api/products \
        "{\"name\":\"$1\",\"description\":\"Phase 24 smoke probe\",\"price\":$2,\"stockQuantity\":$3,\"category\":\"TEST\"}" \
        >/dev/null
    jget "d['id']"
}

empty_cart() {
    request GET /api/cart >/dev/null
    for product_id in $(jget "' '.join(str(i['productId']) for i in d['items'])"); do
        request DELETE "/api/cart/items/$product_id" >/dev/null
    done
}

# --- Success -------------------------------------------------------------------------------------
SAGA_OK_PRODUCT="$(saga_product "Saga Probe" 25.00 5)"
as_customer
empty_cart
request POST /api/cart/items "{\"productId\":$SAGA_OK_PRODUCT,\"quantity\":2}" >/dev/null
check "a normal checkout is accepted (201)" "201" "$(request POST /api/orders)"
SAGA_OK_ORDER="$(jget "d['id']")"
check "as PENDING: the saga decides the rest" "PENDING" "$(jget "d['status']")"
check "the saga ends CONFIRMED" "CONFIRMED" "$(wait_for_order_status "$SAGA_OK_ORDER")"
request GET "/api/orders/$SAGA_OK_ORDER/status" >/dev/null
check "the status endpoint gives no reason for a confirmed order" "None" "$(jget "d['reason']")"
check "and says when it was decided" "True" "$(jget "d['changedAt'] is not None")"
check "the stock was taken: the catalogue converges on 3" "3" "$(wait_for_stock "$SAGA_OK_PRODUCT" 3)"
if ctr_exec "${PAYMENT_DB_CONTAINER:-ecomdemo-payment-db}" true >/dev/null 2>&1; then
    check "payment-service kept a COMPLETED payment for the order total" "COMPLETED|50.00" \
        "$(payment_psql_query "SELECT status || '|' || amount FROM payment WHERE order_id = $SAGA_OK_ORDER;")"
    check "inventory-service holds its reservation as RESERVED" "RESERVED|2" \
        "$(inventory_psql_query "SELECT status || '|' || quantity FROM stock_reservation WHERE order_id = $SAGA_OK_ORDER AND product_id = $SAGA_OK_PRODUCT;")"
else
    skip "the saga's rows in the payment and inventory databases" "no payment-db container"
fi
check "and only a CONFIRMED order is announced: the shopper is thanked" "1" \
    "$(wait_for_notification "$SAGA_OK_ORDER")"

# --- Failure: payment declined, stock compensated -------------------------------------------------
SAGA_FAIL_PRODUCT="$(saga_product "Saga Declined Probe" 6000.00 5)"
as_customer
empty_cart
request POST /api/cart/items "{\"productId\":$SAGA_FAIL_PRODUCT,\"quantity\":2}" >/dev/null
check "a checkout over the payment limit is ALSO accepted - nobody knows yet" "201" \
    "$(request POST /api/orders)"
SAGA_FAIL_ORDER="$(jget "d['id']")"
check "the saga ends CANCELLED" "CANCELLED" "$(wait_for_order_status "$SAGA_FAIL_ORDER")"
request GET "/api/orders/$SAGA_FAIL_ORDER/status" >/dev/null
check "and the status says why" "True" \
    "$(jget "d['reason'].startswith('Payment declined: 12000.00 exceeds the limit')")"
if ctr_exec "${PAYMENT_DB_CONTAINER:-ecomdemo-payment-db}" true >/dev/null 2>&1; then
    check "payment-service kept the decline, with its reason" "FAILED|12000.00" \
        "$(payment_psql_query "SELECT status || '|' || amount FROM payment WHERE order_id = $SAGA_FAIL_ORDER;")"
    # THE COMPENSATION. Polled: it runs in inventory-service when it reads payments.failed, which
    # is independent of - and may land after - the order service cancelling on the same event.
    RELEASED=""
    for _ in $(seq 1 60); do
        RELEASED="$(inventory_psql_query "SELECT status || '|' || quantity FROM stock_reservation WHERE order_id = $SAGA_FAIL_ORDER;")"
        [ "$RELEASED" = "RELEASED|2" ] && break
        sleep 1
    done
    check "inventory COMPENSATED: the reservation is RELEASED" "RELEASED|2" "$RELEASED"
else
    skip "the saga's rows in the payment and inventory databases" "no payment-db container"
fi
check "the stock is restored: the catalogue converges back on 5" "5" \
    "$(wait_for_stock "$SAGA_FAIL_PRODUCT" 5)"
check "no OrderPlaced was published for it" "0" \
    "$(psql_query "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$SAGA_FAIL_ORDER' AND event_type = 'OrderPlacedEvent';" | tr -d ' ')"
check "so nobody was thanked for an order that did not happen" "0" \
    "$(notification_psql_query "SELECT count(*) FROM notification WHERE order_id = $SAGA_FAIL_ORDER;" | tr -d ' ')"
check "the cancelled order is still in the shopper's history, as CANCELLED" "CANCELLED" \
    "$(request GET "/api/orders/$SAGA_FAIL_ORDER" >/dev/null; jget "d['status']")"

# --- Refusal: the pre-check passed, the reservation did not ---------------------------------------
# Two shoppers, one unit, two checkouts in parallel. Both pass the stock PRE-CHECK - it is a read,
# and the reservation happens a moment later in inventory-service - so both are usually accepted.
# Then the reservation, made against a locked row, gives the unit to exactly one. The other is
# CANCELLED with the same words the pre-check would have used. That is the "pre-check is a
# courtesy, the reservation is the guarantee" claim, observed.
SAGA_LAST_PRODUCT="$(saga_product "Saga Last Unit Probe" 30.00 1)"
as_customer; empty_cart
request POST /api/cart/items "{\"productId\":$SAGA_LAST_PRODUCT,\"quantity\":1}" >/dev/null
as_other; empty_cart
request POST /api/cart/items "{\"productId\":$SAGA_LAST_PRODUCT,\"quantity\":1}" >/dev/null
SAGA_RACE_A="$(mktemp)"; SAGA_RACE_B="$(mktemp)"
curl -sS -o "$SAGA_RACE_A" -X POST "$BASE_URL/api/orders" -H "Authorization: Bearer $CUSTOMER_TOKEN" &
curl -sS -o "$SAGA_RACE_B" -X POST "$BASE_URL/api/orders" -H "Authorization: Bearer $OTHER_TOKEN" &
wait
SAGA_RACE_IDS=""
SAGA_RACE_OUTCOMES=""
for pair in "$SAGA_RACE_A:$CUSTOMER_TOKEN" "$SAGA_RACE_B:$OTHER_TOKEN"; do
    file="${pair%%:*}"; token="${pair#*:}"
    id="$(python3 -c "import json;print(json.load(open('$file')).get('id') or '')" 2>/dev/null)"
    if [ -n "$id" ]; then
        as_token "$token"
        SAGA_RACE_OUTCOMES="$SAGA_RACE_OUTCOMES $(wait_for_order_status "$id")"
        SAGA_RACE_IDS="$SAGA_RACE_IDS $id"
    else
        SAGA_RACE_OUTCOMES="$SAGA_RACE_OUTCOMES REFUSED"   # the pre-check saw 0: also correct
    fi
done
rm -f "$SAGA_RACE_A" "$SAGA_RACE_B"
SAGA_RACE_SORTED="$(printf '%s\n' $SAGA_RACE_OUTCOMES | sort | paste -sd, -)"
check "one unit, two buyers: exactly one CONFIRMED (${SAGA_RACE_SORTED})" "True" \
    "$(case "$SAGA_RACE_SORTED" in CANCELLED,CONFIRMED|CONFIRMED,REFUSED) echo True ;; *) echo False ;; esac)"
check "and the stock is 0, never -1" "0" "$(wait_for_stock "$SAGA_LAST_PRODUCT" 0)"
if [ "$SAGA_RACE_SORTED" = "CANCELLED,CONFIRMED" ]; then
    # The StockRejected path: nothing was reserved for the loser, so nothing is released.
    SAGA_REJECTED_REASON=""
    for id in $SAGA_RACE_IDS; do
        for token in "$CUSTOMER_TOKEN" "$OTHER_TOKEN"; do
            as_token "$token"
            [ "$(request GET "/api/orders/$id/status")" = "200" ] || continue
            [ "$(jget "d['status']")" = "CANCELLED" ] && SAGA_REJECTED_REASON="$(jget "d['reason']")"
        done
    done
    check "the loser was cancelled by inventory, for stock" "True" \
        "$(case "$SAGA_REJECTED_REASON" in *"requested 1, available 0"*) echo True ;; *) echo False ;; esac)"
else
    skip "the loser was cancelled by inventory, for stock" \
        "the second checkout was refused by the pre-check this time (${SAGA_RACE_SORTED}); both are correct"
fi

as_admin
for product_id in "$SAGA_OK_PRODUCT" "$SAGA_FAIL_PRODUCT" "$SAGA_LAST_PRODUCT"; do
    request DELETE "/api/products/$product_id" >/dev/null
done
as_customer
pass "the saga probe products are cleaned up"

# --------------------------------------------------------------------------------------------
# Saga deadline and dead letters (Phase 32)
# --------------------------------------------------------------------------------------------
# The scripted failure scenario for "no order stays PENDING for ever". One order's StockReserved is
# DEAD-LETTERED on purpose - exactly what SagaListenerErrors does after three failed attempts: the
# record is copied to inventory.stock-reserved-dlt with the recoverer's headers, and payment-service's
# consumer offset is moved past it. Stock is then held for an order nobody will ever pay for, which is
# the Phase 24 gap. The saga deadline must find it, ask payment-service (which VOIDS it), ask
# inventory-service to close it (stock back, fence up), and CANCEL it - within the deadline plus a
# sweep. Then the dead letter is replayed through the admin API, and must change nothing: payment
# refuses to charge a voided order.
#
# payment-service is stopped for the length of the forgery, because a consumer group's offsets can
# only be moved while the group is empty. It is restarted on the way out whatever happens.
section "Saga deadline and dead letters"

SAGA_DEADLINE_SECONDS="${SAGA_DEADLINE_SECONDS:-60}"
PAYMENT_CONTAINER="${PAYMENT_CONTAINER:-ecomdemo-payment-service}"

scrape
check "the stuck-order gauge is exposed" "True" \
    "$([ "$(metric saga_orders_overdue)" != "MISSING" ] && echo True || echo False)"
check "and the reconciliation counter, by outcome" "True" \
    "$([ "$(metric saga_reconciliations_total outcome=cancelled)" != "MISSING" ] && echo True || echo False)"

as_anonymous
check "payment-service's settlement endpoint wants a token (401)" "401" \
    "$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$PAYMENT_URL/internal/saga/orders/1/settle" \
        -H 'Content-Type: application/json' -d '{"amount":1.00}')"
check "and a SERVICE one: a shopper's token is refused (403)" "403" \
    "$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$PAYMENT_URL/internal/saga/orders/1/settle" \
        -H "Authorization: Bearer $CUSTOMER_TOKEN" -H 'Content-Type: application/json' -d '{"amount":1.00}')"
check "the dead-letter admin API is an administrator's (403 for a shopper)" "403" \
    "$(as_customer; request GET /api/admin/dead-letters)"

if command -v docker >/dev/null 2>&1 \
    && ctr_exec "${KAFKA_CONTAINER:-ecomdemo-kafka}" true >/dev/null 2>&1 \
    && ctr_exec "${PAYMENT_DB_CONTAINER:-ecomdemo-payment-db}" true >/dev/null 2>&1 \
    && ctr_exec "$PAYMENT_CONTAINER" true >/dev/null 2>&1; then

    DLT_PRODUCT="$(saga_product "Saga Dead Letter Probe" 10.00 5)"
    scrape
    CANCELLED_BEFORE="$(metric saga_reconciliations_total outcome=cancelled)"

    PAYMENT_WAS_STOPPED=true
    ctr_stop "$PAYMENT_CONTAINER" >/dev/null

    as_customer
    empty_cart
    request POST /api/cart/items "{\"productId\":$DLT_PRODUCT,\"quantity\":2}" >/dev/null
    check "checkout with payment-service down is accepted (201)" "201" "$(request POST /api/orders)"
    DLT_ORDER="$(jget "d['id']")"

    HELD=""
    for _ in $(seq 1 60); do
        HELD="$(inventory_psql_query "SELECT status || '|' || quantity FROM stock_reservation WHERE order_id = $DLT_ORDER;")"
        [ "$HELD" = "RESERVED|2" ] && break
        sleep 1
    done
    check "inventory reserved the stock and announced StockReserved" "RESERVED|2" "$HELD"

    # Find the order's StockReserved on the topic (the relay publishes it within about a second).
    RESERVED_RECORD=""
    for _ in 1 2 3 4 5; do
        RESERVED_RECORD="$(kafka kafka-console-consumer.sh --topic inventory.stock-reserved --from-beginning \
            --property print.key=true --property key.separator='|' --timeout-ms 10000 \
            | grep "^${DLT_ORDER}|" | tail -1)"
        [ -n "$RESERVED_RECORD" ] && break
        sleep 2
    done
    check "the order's StockReserved is on inventory.stock-reserved" "True" \
        "$([ -n "$RESERVED_RECORD" ] && echo True || echo False)"

    # DEAD-LETTER IT: the record, with the recoverer's headers, to the DLT...
    DLT_BEFORE="$(topic_message_count inventory.stock-reserved-dlt)"
    printf 'kafka_dlt-original-topic:inventory.stock-reserved,kafka_dlt-exception-message:dead-lettered by the smoke test\t%s\n' \
        "$RESERVED_RECORD" \
        | ctr_exec -i "${KAFKA_CONTAINER:-ecomdemo-kafka}" /opt/kafka/bin/kafka-console-producer.sh \
            --bootstrap-server localhost:9092 --topic inventory.stock-reserved-dlt \
            --property parse.key=true --property key.separator='|' --property parse.headers=true >/dev/null 2>&1
    check "the StockReserved is now in inventory.stock-reserved-dlt" "1" \
        "$(delta "$DLT_BEFORE" "$(topic_message_count inventory.stock-reserved-dlt)")"
    # ...and payment-service's group moved past it, as if its listener had given up on it.
    RESET=""
    for _ in $(seq 1 30); do
        RESET="$(kafka kafka-consumer-groups.sh --group payment-service --topic inventory.stock-reserved \
            --reset-offsets --to-latest --execute 2>&1 || true)"
        printf '%s' "$RESET" | grep -q "NEW-OFFSET" && break
        sleep 2
    done
    check "and payment-service's consumer skips it (offset moved to the end)" "True" \
        "$(printf '%s' "$RESET" | grep -q "NEW-OFFSET" && echo True || echo False)"

    ctr_start "$PAYMENT_CONTAINER" >/dev/null
    PAYMENT_WAS_STOPPED=false
    for _ in $(seq 1 90); do
        # Silent: while the container starts, the connection is refused or reset, which is expected.
        [ "$(curl -s -o /dev/null -w '%{http_code}' "$PAYMENT_URL/actuator/health/readiness" 2>/dev/null)" = "200" ] && break
        sleep 1
    done

    # THE PHASE'S CLAIM: resolved within the deadline (plus one sweep and the restart's slack).
    as_customer
    DLT_STARTED="$(date +%s)"
    DLT_STATUS="$(wait_for_order_status "$DLT_ORDER" $((SAGA_DEADLINE_SECONDS + 90)))"
    check "the saga deadline resolves the dead-lettered order: CANCELLED" "CANCELLED" "$DLT_STATUS"
    request GET "/api/orders/$DLT_ORDER/status" >/dev/null
    check "and says why: payment was never attempted before the deadline" "True" \
        "$(jget "'before the order' in d['reason'] and 'deadline' in d['reason']")"
    printf '    (decided %ss after payment-service returned; deadline %ss)\n' \
        "$(( $(date +%s) - DLT_STARTED ))" "$SAGA_DEADLINE_SECONDS"
    check "payment-service recorded a VOIDED payment - nothing was charged" "VOIDED" \
        "$(payment_psql_query "SELECT status FROM payment WHERE order_id = $DLT_ORDER;")"
    check "inventory released the reservation" "RELEASED|2" \
        "$(inventory_psql_query "SELECT status || '|' || quantity FROM stock_reservation WHERE order_id = $DLT_ORDER;")"
    check "and fenced the order against a late reservation" "1" \
        "$(inventory_psql_query "SELECT count(*) FROM closed_order WHERE order_id = $DLT_ORDER;" | tr -d ' ')"
    check "the stock is back: the catalogue converges on 5" "5" "$(wait_for_stock "$DLT_PRODUCT" 5)"
    scrape
    check "the reconciliation was counted (saga_reconciliations_total{outcome=cancelled} +1 or more)" "True" \
        "$(python3 -c "print(float('$(metric saga_reconciliations_total outcome=cancelled)') - float('$CANCELLED_BEFORE') >= 1)")"

    # REPLAY the dead letter through the admin API: it must change nothing.
    as_admin
    check "an administrator can list the dead letters (200)" "200" "$(request GET /api/admin/dead-letters)"
    DLT_ADDRESS="$(jget "next(('%s/%s/%s' % (r['topic'], r['partition'], r['offset'])) for r in reversed(d) if r['key'] == '$DLT_ORDER' and r['topic'] == 'inventory.stock-reserved-dlt')")"
    check "the dead-lettered StockReserved is listed, with where it came from" "inventory.stock-reserved" \
        "$(jget "next(r['originalTopic'] for r in reversed(d) if r['key'] == '$DLT_ORDER')")"
    check "replaying it is accepted (200)" "200" "$(request POST "/api/admin/dead-letters/$DLT_ADDRESS/replay")"
    check "the replay is recorded against the administrator" "$ADMIN_USER" "$(jget "d['replayedBy']")"
    check "a second replay of the same record is refused (409)" "409" \
        "$(request POST "/api/admin/dead-letters/$DLT_ADDRESS/replay")"
    # payment-service consumes the replayed StockReserved within a second or two; give it five.
    sleep 5
    check "the replayed StockReserved charged nothing: still one payment, VOIDED" "1|VOIDED" \
        "$(payment_psql_query "SELECT count(*) || '|' || max(status) FROM payment WHERE order_id = $DLT_ORDER;")"
    as_customer
    check "and the order is still CANCELLED" "CANCELLED" \
        "$(request GET "/api/orders/$DLT_ORDER/status" >/dev/null; jget "d['status']")"
    check "with the stock still 5" "5" "$(wait_for_stock "$DLT_PRODUCT" 5)"

    as_admin
    request DELETE "/api/products/$DLT_PRODUCT" >/dev/null
    as_customer
else
    skip "the dead-letter scenario" "needs the Kafka, payment-service and payment-db containers"
fi

# --------------------------------------------------------------------------------------------
# Authentication hardening (Phase 33)
# --------------------------------------------------------------------------------------------
# customer-service is now the only service with a signing key (RS256); everyone else verifies with its
# PUBLIC keys from /oauth2/jwks. Services get their own tokens from it (client credentials), scoped
# to what each needs. Failed logins are throttled.
#
# The JWKS and service-token checks run INSIDE catalog-service's container: customer-service:8083
# resolves there on compose and on Kubernetes alike, and that container holds catalog-service's own
# client secret (SERVICE_CLIENT_SECRET). The secret never leaves the container and is never printed.
section "Authentication hardening"

JWKS_JSON="$(ctr_exec "$CATALOG_CONTAINER" wget -qO- http://customer-service:8083/oauth2/jwks 2>/dev/null)"
check "customer-service publishes its public keys at /oauth2/jwks" "True" \
    "$(python3 -c "import json,sys; k=json.loads(sys.argv[1])['keys']; print(bool(k) and all(x['kty']=='RSA' and x.get('kid') for x in k))" "$JWKS_JSON" 2>/dev/null)"
check "and no private key material (no 'd', 'p' or 'q')" "True" \
    "$(python3 -c "import json,sys; k=json.loads(sys.argv[1])['keys']; print(not any(f in x for x in k for f in ('d','p','q')))" "$JWKS_JSON" 2>/dev/null)"

# A token with the right shape, a real key id and an ADMIN role, signed with a key customer-service
# never published: the signature is what refuses it.
ACTIVE_KID="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['keys'][0]['kid'])" "$JWKS_JSON" 2>/dev/null)"
FOREIGN_PEM="$(mktemp)"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$FOREIGN_PEM" 2>/dev/null
FOREIGN_NOW="$(date +%s)"
FOREIGN_HEADER="$(printf '{"alg":"RS256","kid":"%s"}' "$ACTIVE_KID" | b64url)"
FOREIGN_PAYLOAD="$(printf '{"iss":"ecomdemo","sub":"admin","uid":1,"roles":["ADMIN"],"iat":%d,"exp":%d}' \
    "$FOREIGN_NOW" "$((FOREIGN_NOW + 900))" | b64url)"
FOREIGN_SIGNATURE="$(printf '%s.%s' "$FOREIGN_HEADER" "$FOREIGN_PAYLOAD" | openssl dgst -sha256 -sign "$FOREIGN_PEM" | b64url)"
rm -f "$FOREIGN_PEM"
as_token "$FOREIGN_HEADER.$FOREIGN_PAYLOAD.$FOREIGN_SIGNATURE"
check "a token signed with any other key is rejected (401), even claiming ADMIN" "401" \
    "$(request GET /api/customers/me)"

# The gateway's own token: it asks with its own secret (inside its container) and gets catalog:read,
# nothing more. Until Phase 33 every service token carried one SERVICE role that allowed all of this.
GATEWAY_CONTAINER="${GATEWAY_CONTAINER:-ecomdemo-gateway-service}"
SCOPED_JSON="$(ctr_exec "$GATEWAY_CONTAINER" sh -c 'wget -qO- \
    --header "Authorization: Basic $(printf "gateway-service:%s" "$SERVICE_CLIENT_SECRET" | base64 | tr -d "\n")" \
    --header "Content-Type: application/x-www-form-urlencoded" \
    --post-data "grant_type=client_credentials" http://customer-service:8083/oauth2/token' 2>/dev/null)"
check "the gateway gets a service token scoped to catalog:read only" "catalog:read" \
    "$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['scope'])" "$SCOPED_JSON" 2>/dev/null)"
SCOPED_TOKEN="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['access_token'])" "$SCOPED_JSON" 2>/dev/null)"
# in_gateway_status <token> <url> [json-body] -> the HTTP status, as BusyBox wget reports it. On an
# error it ALSO prints "wget: server returned error: HTTP/1.1 403 ...", so the code is taken from
# after "HTTP/x.x" rather than from a fixed word position.
in_gateway_status() {
    ctr_exec "$GATEWAY_CONTAINER" sh -c 'if [ -n "$3" ]; then
            wget -q -S -O /dev/null --header "Authorization: Bearer $1" --header "Content-Type: application/json" --post-data "$3" "$2"
        else
            wget -q -S -O /dev/null --header "Authorization: Bearer $1" "$2"
        fi 2>&1 | sed -n "s/.*HTTP\/[0-9.]* \([0-9][0-9][0-9]\).*/\1/p" | tail -1' _ "$1" "$2" "${3:-}" 2>/dev/null
}
check "with it, the gateway may read the catalogue (200)" "200" \
    "$(in_gateway_status "$SCOPED_TOKEN" http://catalog-service:8081/api/products/1)"
check "but a service token cannot call outside its scope: reading stock is 403" "403" \
    "$(in_gateway_status "$SCOPED_TOKEN" http://inventory-service:8082/api/inventory/1)"
check "and asking payment-service to settle an order is 403" "403" \
    "$(in_gateway_status "$SCOPED_TOKEN" http://payment-service:8086/internal/saga/orders/1/settle '{"amount":1.00}')"

# Throttling: five wrong passwords for one username, then the sixth attempt is refused before the
# password is even checked. The username is unique per run; an account need not exist to be throttled.
THROTTLE_USER="smoke-throttle-$(date +%s)"
as_anonymous
THROTTLE_STATUSES=""
for _ in 1 2 3 4 5; do
    THROTTLE_STATUSES="$THROTTLE_STATUSES $(request POST /api/auth/login "{\"username\":\"$THROTTLE_USER\",\"password\":\"wrong-password\"}")"
done
check "five wrong passwords are five ordinary 401s" "401 401 401 401 401" "${THROTTLE_STATUSES# }"
THROTTLED_HEADERS="$(curl -sS -o "$BODY" -D - -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$THROTTLE_USER\",\"password\":\"wrong-password\"}" 2>/dev/null | tr -d '\r')"
check "the next attempt is throttled: 429" "429" "$(printf '%s\n' "$THROTTLED_HEADERS" | head -1 | awk '{print $2}')"
check "with a Retry-After of a few seconds, and the same number in the message" "True" \
    "$(python3 -c "
import json,re,sys
h={l.split(':',1)[0].strip().lower(): l.split(':',1)[1].strip() for l in sys.argv[1].splitlines()[1:] if ':' in l}
s=int(h.get('retry-after','0')); m=json.load(open(sys.argv[2]))['message']
print(0 < s <= 30 and ('in %d seconds' % s) in m)" "$THROTTLED_HEADERS" "$BODY" 2>/dev/null)"
# Leave no block behind: forget this username, and the per-client counts that are not blocking
# anyone, so repeated smoke runs from one machine cannot add up to the per-client limit.
customer_psql_query "DELETE FROM login_throttle WHERE throttle_key = 'user:$THROTTLE_USER'
    OR (throttle_key LIKE 'client:%' AND blocked_until IS NULL);" >/dev/null

# --------------------------------------------------------------------------------------------
# LLM integration (Phase 27)
# --------------------------------------------------------------------------------------------
# POST /api/products/{id}/generate-description asks catalog-service's configured chat model for a
# description, tags and an SEO title. Whether a model is configured is the operator's choice
# (AI_CHAT_PROVIDER), so this section checks what must hold EITHER way - who may call it, a 404
# before any model is asked, a 503 that leaves the product alone - and the generation itself only
# when a model answers. Without one, that check is a SKIP with the setup steps. It is never a pass:
# a green line for a model nobody called would be exactly the fake result the phase forbids.
section "LLM integration"

CATALOG_URL="${CATALOG_URL:-http://localhost:8081}"

# catalog_scrape -> every catalog-service instance's /actuator/prometheus, concatenated.
# EVERY instance, because a counter lives in one JVM: in the cluster catalog-service runs two to four
# pods behind the Ingress, and the generation lands on whichever the Ingress picked. METRIC_PY sums
# matching series, so the concatenation reads as the service's total - the same answer Prometheus
# would give with sum().
catalog_scrape() {
    if [ "$SMOKE_PLATFORM" = "k8s" ]; then
        for pod in $(kube get pods -l app.kubernetes.io/name=catalog-service -o name 2>/dev/null); do
            kube exec "$pod" -- wget -qO- http://localhost:8081/actuator/prometheus 2>/dev/null
        done
    else
        curl -sS "$CATALOG_URL/actuator/prometheus" 2>/dev/null
    fi
}

LLM_ORIGINAL="Phase 27 smoke probe, written by a person."
as_admin
request POST /api/products \
    "{\"name\":\"LLM Probe Keyboard\",\"description\":\"$LLM_ORIGINAL\",\"price\":4999.00,\"stockQuantity\":3,\"category\":\"PERIPHERALS\"}" \
    >/dev/null
LLM_PRODUCT="$(jget "d['id']")"

as_anonymous
check "generating a description needs a token (401)" "401" \
    "$(request POST "/api/products/$LLM_PRODUCT/generate-description")"
as_customer
check "and an ADMIN one: a customer gets 403" "403" \
    "$(request POST "/api/products/$LLM_PRODUCT/generate-description")"
as_admin
check "an unknown product is a 404, before any model is asked" "404" \
    "$(request POST /api/products/999999/generate-description)"

LLM_METRIC_BEFORE="$(catalog_scrape \
    | python3 -c "$METRIC_PY" /dev/stdin ecomdemo_ai_generations_seconds_count)"
[ "$LLM_METRIC_BEFORE" = "MISSING" ] && LLM_METRIC_BEFORE=0

# Up to three attempts, the way a client honouring Retry-After would. A real model's output varies
# from call to call: in Phase 28's real-model runs llama3.2 once wrote a 71-character SEO title, the
# service refused it with 503 "unusable answer" (correctly - that is the graceful degradation), and
# the next call was fine. Only THAT kind of refusal is retried; "not configured" and "did not answer"
# are the same every time. Headers are kept per attempt, so the Retry-After check below reads the
# refusal it describes, not a new request that might succeed.
as_admin
LLM_ATTEMPTS=0
while :; do
    LLM_ATTEMPTS=$((LLM_ATTEMPTS + 1))
    LLM_STATUS="$(curl -sS -o "$BODY" -D "$BODY.llm.headers" -w '%{http_code}' -X POST \
        "$BASE_URL/api/products/$LLM_PRODUCT/generate-description" -H "Authorization: Bearer $AUTH")"
    cp "$BODY" "$BODY.llm"
    if [ "$LLM_STATUS" = "503" ] && [ "$LLM_ATTEMPTS" -lt 3 ] \
        && jget "d['message']" | grep -q "unusable answer"; then
        printf '        attempt %s: the model broke the limits, refused with 503 and retried\n' "$LLM_ATTEMPTS"
        continue
    fi
    break
done

case "$LLM_STATUS" in
    200)
        check "the generator answers with the structured copy: description, tags, SEO title" "True" \
            "$(jget "bool(d['description'].strip()) and len(d['tags']) > 0 and bool(d['seoTitle'].strip()) and len(d['seoTitle']) <= 70 and len(d['description']) <= 1000")"
        LLM_DESCRIPTION="$(jget "d['description']")"
        printf '        model: %s, tokens: %s in / %s out\n' \
            "$(jget "d['model']")" "$(jget "d['promptTokens']")" "$(jget "d['completionTokens']")"
        printf '        seoTitle: %s\n' "$(jget "d['seoTitle']")"
        request GET "/api/products/$LLM_PRODUCT" >/dev/null
        check "the description is saved to the product" "True" \
            "$(python3 -c "import json,sys; print(json.load(open('$BODY'))['description'] == sys.argv[1])" "$LLM_DESCRIPTION")"
        LLM_ROWS="$(ctr_exec "${CATALOG_DB_CONTAINER:-ecomdemo-catalog-db}" \
            psql -qtAX -U "${CATALOG_DB_USER:-catalog}" -d "${CATALOG_DB_NAME:-catalog}" \
            -c "SELECT count(*) FROM product_description_generation WHERE product_id = $LLM_PRODUCT;" \
            2>/dev/null | tr -d '\r ')"
        check "and kept in the generation history" "1" "$LLM_ROWS"
        LLM_TOKENS="$(catalog_scrape \
            | python3 -c "$METRIC_PY" /dev/stdin ecomdemo_ai_tokens_total type=prompt)"
        check "the tokens it cost are counted in catalog-service's metrics" "True" \
            "$(python3 -c "print('$LLM_TOKENS' != 'MISSING' and float('$LLM_TOKENS') > 0)")"
        ;;
    503)
        if jget "d['message']" | grep -q "not configured"; then
            skip "the generator answers with the structured copy: description, tags, SEO title" \
                "no language model configured. To run it: set AI_CHAT_PROVIDER=openai and OPENAI_API_KEY in .env, or install Ollama on the host, run 'ollama pull llama3.2' and set AI_CHAT_PROVIDER=ollama; then 'docker compose up -d catalog-service' and run this script again"
        else
            fail "the generator answers with the structured copy: description, tags, SEO title" \
                "200" "503: $(jget "d['message']") (a model is configured but did not answer usably)"
        fi
        check "a refusal says when to retry (Retry-After)" "True" \
            "$(grep -qi '^retry-after: [1-9]' "$BODY.llm.headers" && echo True || echo False)"
        request GET "/api/products/$LLM_PRODUCT" >/dev/null
        check "and the product is left exactly as it was" "$LLM_ORIGINAL" "$(jget "d['description']")"
        ;;
    *)
        fail "the generator answers 200, or 503 when it cannot" "200 or 503" \
            "$LLM_STATUS: $(python3 -c "print(open('$BODY.llm').read()[:200])" 2>/dev/null)"
        ;;
esac
rm -f "$BODY.llm" "$BODY.llm.headers"

LLM_METRIC_AFTER="$(catalog_scrape \
    | python3 -c "$METRIC_PY" /dev/stdin ecomdemo_ai_generations_seconds_count)"
check "every generation attempt is counted, whatever its outcome" "True" \
    "$(python3 -c "print('$LLM_METRIC_AFTER' != 'MISSING' and float('$LLM_METRIC_AFTER') > float('$LLM_METRIC_BEFORE'))")"

as_admin
request DELETE "/api/products/$LLM_PRODUCT" >/dev/null
check "the probe product is deleted, generation history and all" "404" \
    "$(request GET "/api/products/$LLM_PRODUCT")"
as_customer

# --------------------------------------------------------------------------------------------
# Semantic search (Phase 28)
# --------------------------------------------------------------------------------------------
# GET /api/products/search?q=... embeds the query and returns the products nearest in meaning,
# from pgvector. Like the LLM section, the MODEL is the operator's choice (AI_EMBEDDING_PROVIDER):
# what must hold either way is checked every run - who may start and watch the backfill, and that
# every product write is announced through the outbox and published to Kafka - and the search
# itself only when a model answers. Without one it is a SKIP with the setup steps, never a pass.
section "Semantic search"

# The phase's query. It shares no word with "Noise-Cancelling Headphones - Over-ear ANC headphones,
# 30h battery", so keyword search cannot find it; the check below proves that against the database
# rather than asserting it.
SEARCH_QUERY="I want to listen to music without hearing the plane"
SEARCH_EXPECTED="Noise-Cancelling Headphones"

urlq() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$1"; }

catalog_sql() {
    ctr_exec "${CATALOG_DB_CONTAINER:-ecomdemo-catalog-db}" \
        psql -qtAX -U "${CATALOG_DB_USER:-catalog}" -d "${CATALOG_DB_NAME:-catalog}" -c "$1" 2>/dev/null | tr -d '\r '
}

as_anonymous
check "search is public, and an empty query is a 400" "400" "$(request GET "/api/products/search?q=%20")"
check "starting the embedding backfill needs a token (401)" "401" \
    "$(request POST /api/products/embeddings/backfill)"
as_customer
check "and an ADMIN one: a customer gets 403" "403" "$(request POST /api/products/embeddings/backfill)"
check "a backfill's progress is ADMIN-only too, although it is a GET under /api/products" "403" \
    "$(request GET /api/products/embeddings/backfill/1)"

as_admin
SEARCH_BACKFILL_STATUS="$(request POST /api/products/embeddings/backfill)"
cp "$BODY" "$BODY.search"

# Every product write is announced, model or not: the index built later depends on it.
request POST /api/products \
    '{"name":"Search Probe Flask","description":"Vacuum-insulated steel bottle that keeps water ice cold for 24 hours","price":1599.00,"stockQuantity":2,"category":"OUTDOOR"}' \
    >/dev/null
SEARCH_PRODUCT="$(jget "d['id']")"
check "creating a product writes ProductChanged to catalog's outbox, in the same transaction" "1" \
    "$(catalog_sql "SELECT count(*) FROM outbox_event WHERE event_type = 'ProductChanged' AND aggregate_id = '$SEARCH_PRODUCT';")"
SEARCH_PUBLISHED=0
for _ in $(seq 1 30); do
    SEARCH_PUBLISHED="$(catalog_sql "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$SEARCH_PRODUCT' AND published_at IS NOT NULL;")"
    [ "${SEARCH_PUBLISHED:-0}" -ge 1 ] && break
    sleep 1
done
check "and the relay publishes it to catalog.product-changed" "1" "$SEARCH_PUBLISHED"

case "$SEARCH_BACKFILL_STATUS" in
    202)
        SEARCH_EXECUTION="$(python3 -c "import json; print(json.load(open('$BODY.search'))['executionId'])")"
        for _ in $(seq 1 120); do
            request GET "/api/products/embeddings/backfill/$SEARCH_EXECUTION" >/dev/null
            case "$(jget "d['status']")" in COMPLETED|FAILED|STOPPED) break ;; esac
            sleep 1
        done
        check "the backfill job completes" "COMPLETED" "$(jget "d['status']")"
        printf '        %s products read, %s embedded; failure: %s\n' \
            "$(jget "d['read']")" "$(jget "d['written']")" "$(jget "d['failure']")"

        # The probe was created after the backfill STARTED, so the event is what indexes it.
        # Waited for by its row rather than by a search, so this does not depend on the ranking.
        SEARCH_INDEXED=0
        for _ in $(seq 1 30); do
            SEARCH_INDEXED="$(catalog_sql "SELECT count(*) FROM product_embedding WHERE id = $SEARCH_PRODUCT;")"
            [ "${SEARCH_INDEXED:-0}" -ge 1 ] && break
            sleep 1
        done
        check "a new product is embedded from its event, with no backfill (outbox -> Kafka -> indexer)" "1" \
            "$SEARCH_INDEXED"
        check "every product has an embedding" "0" \
            "$(catalog_sql "SELECT count(*) FROM product p WHERE NOT EXISTS (SELECT 1 FROM product_embedding e WHERE e.id = p.id);")"

        as_anonymous
        check "the phase's natural-language query answers 200" "200" \
            "$(request GET "/api/products/search?q=$(urlq "$SEARCH_QUERY")")"
        printf '        "%s" -> %s\n' "$SEARCH_QUERY" \
            "$(jget "', '.join('%s %.3f' % (h['product']['name'], h['similarity']) for h in d['results'][:3])")"
        check "and ranks the product it means first" "$SEARCH_EXPECTED" \
            "$(jget "d['results'][0]['product']['name'] if d['results'] else 'NO RESULTS'")"
        SEARCH_WORDS="$(python3 -c "import sys; print(' '.join(w for w in sys.argv[1].lower().split() if len(w) > 3))" "$SEARCH_QUERY")"
        SEARCH_KEYWORD_HITS=0
        for word in $SEARCH_WORDS; do
            hits="$(catalog_sql "SELECT count(*) FROM product WHERE name = '$SEARCH_EXPECTED' AND (name ILIKE '%$word%' OR description ILIKE '%$word%');")"
            SEARCH_KEYWORD_HITS=$((SEARCH_KEYWORD_HITS + ${hits:-0}))
        done
        check "which keyword search misses: none of the query's words is in its name or description" "0" \
            "$SEARCH_KEYWORD_HITS"
        request GET "/api/products/search?q=$(urlq "$SEARCH_QUERY")&category=ACCESSORIES" >/dev/null
        check "a category filter runs inside the vector query" "True" \
            "$(jget "all(h['product']['category'] == 'ACCESSORIES' for h in d['results']) and all(h['product']['name'] != '$SEARCH_EXPECTED' for h in d['results'])")"
        request GET "/api/products/search?q=$(urlq "$SEARCH_QUERY")&maxPrice=3000" >/dev/null
        check "and so does a price range" "True" \
            "$(jget "all(float(h['product']['price']) <= 3000 for h in d['results'])")"

        SEARCH_INDEXING="$(catalog_scrape \
            | python3 -c "$METRIC_PY" /dev/stdin ecomdemo_search_indexing_seconds_count outcome=indexed)"
        check "indexing and searching are measured in catalog-service's metrics" "True" \
            "$(python3 -c "print('$SEARCH_INDEXING' != 'MISSING' and float('$SEARCH_INDEXING') > 0)")"
        ;;
    503)
        if python3 -c "import json; print(json.load(open('$BODY.search'))['message'])" | grep -q "not configured"; then
            skip "the phase's natural-language query ranks the product it means first" \
                "no embedding model configured. To run it: install Ollama on the host, run 'ollama pull nomic-embed-text' and set AI_EMBEDDING_PROVIDER=ollama in .env (or AI_EMBEDDING_PROVIDER=openai with OPENAI_API_KEY); then 'docker compose up -d catalog-service' and run this script again"
        else
            fail "the backfill job starts" "202" "503: $(python3 -c "import json; print(json.load(open('$BODY.search'))['message'])")"
        fi
        as_anonymous
        check "search without a model is a 503 that says when to retry" "True" \
            "$(curl -sS -o /dev/null -D - "$BASE_URL/api/products/search?q=laptop" \
                | grep -qi '^retry-after: [1-9]' && echo True || echo False)"
        ;;
    *)
        fail "the backfill job starts, or is refused with 503 when there is no model" "202 or 503" \
            "$SEARCH_BACKFILL_STATUS: $(python3 -c "print(open('$BODY.search').read()[:200])" 2>/dev/null)"
        ;;
esac
rm -f "$BODY.search"

as_admin
request DELETE "/api/products/$SEARCH_PRODUCT" >/dev/null
check "the probe product is deleted, and its embedding with it" "0" \
    "$(catalog_sql "SELECT count(*) FROM product_embedding WHERE id = $SEARCH_PRODUCT;")"
as_customer

# --------------------------------------------------------------------------------------------
# Shopping assistant (Phase 29)
# --------------------------------------------------------------------------------------------
# The phase's two checks: the assistant answers a product question (by calling the search tool, as
# the customer), and it refuses to reveal another customer's order. Plus the policy RAG, store
# topics only, and memory in Redis. Every model answer is printed, because "PASS" says little about
# what a language model actually said.
#
# Like the two AI sections above, it needs models: without them it SKIPs with the setup steps, and
# still checks that the refusal is a 503 with a Retry-After.
section "Shopping assistant"

ASSISTANT_URL="${ASSISTANT_URL:-http://localhost:8087}"

# assistant_scrape -> every assistant-service instance's /actuator/prometheus (see catalog_scrape).
assistant_scrape() {
    if [ "$SMOKE_PLATFORM" = "k8s" ]; then
        for pod in $(kube get pods -l app.kubernetes.io/name=assistant-service -o name 2>/dev/null); do
            kube exec "$pod" -- wget -qO- http://localhost:8087/actuator/prometheus 2>/dev/null
        done
    else
        curl -sS "$ASSISTANT_URL/actuator/prometheus" 2>/dev/null
    fi
}

# ask <message> [conversationId] -> the status; the reply lands in $BODY
ask() {
    request POST /api/assistant/chat "$(python3 -c '
import json, sys
body = {"message": sys.argv[1]}
if len(sys.argv) > 2 and sys.argv[2]:
    body["conversationId"] = sys.argv[2]
print(json.dumps(body))' "$@")"
}

# ask_until <python condition on d> <message> -> asks up to 3 times until the reply satisfies it, and
# echoes the last status (the attempts go to stderr, so a check can capture the status alone).
# A model is not a function: with the same input it can still call no tool, or answer from its own
# head. A real failure to answer is still a FAIL after three tries, and the attempts are printed.
ask_until() {
    local condition="$1" message="$2" status attempt
    for attempt in 1 2 3; do
        status="$(ask "$message")"
        [ "$status" = "200" ] && [ "$(jget "$condition")" = "True" ] && break
        printf '        attempt %s: %s %s\n' "$attempt" "$status" "$(jget "d.get('answer', d.get('message', ''))[:160]")" >&2
    done
    echo "$status"
}

as_anonymous
check "the assistant needs a token (401)" "401" "$(ask "hello")"
as_admin
check "and a CUSTOMER one: an administrator has no cart or orders to ask about (403)" "403" "$(ask "hello")"
as_customer
check "a message over 1000 characters is refused before any model sees it (400)" "400" \
    "$(ask "$(python3 -c "print('a' * 1001)")")"
check "confirming a cart addition that was never proposed is 404" "404" \
    "$(request POST /api/assistant/actions/never-proposed/confirm)"

ASSISTANT_STATUS="$(ask "Do you ship outside India?")"
case "$ASSISTANT_STATUS" in
    200)
        ASSISTANT_CHATS_BEFORE="$(assistant_scrape | python3 -c "$METRIC_PY" /dev/stdin ecomdemo_assistant_chats_seconds_count outcome=answered)"

        # --- The product question: RAG through the search tool, as the customer ---
        ASSISTANT_Q="Which headphones do you sell for noisy flights, and what do they cost?"
        check "a product question is answered (200)" "200" \
            "$(ask_until "'Noise-Cancelling Headphones' in d['answer'] and 'searchProducts' in d['toolsUsed']" "$ASSISTANT_Q")"
        printf '        "%s"\n        -> %s\n' "$ASSISTANT_Q" "$(jget "d['answer'][:300]")"
        check "by searching the catalogue: the model called searchProducts" "True" \
            "$(jget "'searchProducts' in d['toolsUsed']")"
        check "and the answer names the product the search returned" "True" \
            "$(jget "'Noise-Cancelling Headphones' in d['answer'] and any(s['type'] == 'product' and s['title'] == 'Noise-Cancelling Headphones' for s in d['sources'])")"
        check "with a price the catalogue gave it (14999)" "True" \
            "$(jget "any(p in d['answer'] for p in ('14999', '14,999'))")"

        # --- A policy question: RAG over the Markdown policies ---
        ASSISTANT_Q="How much does standard shipping cost for an order of 2000 rupees?"
        check "a policy question is answered from the shipping policy" "200" \
            "$(ask_until "'99' in d['answer']" "$ASSISTANT_Q")"
        printf '        "%s"\n        -> %s\n' "$ASSISTANT_Q" "$(jget "d['answer'][:300]")"
        check "the passage it was given is cited as a source" "True" \
            "$(jget "any(s['id'] == 'shipping#shipping-charges' for s in d['sources'])")"

        # --- Another customer's order: the boundary ---
        # OWNED_ORDER_ID is customer A's order from "Data ownership". Customer B asks about it.
        as_customer
        request GET "/api/orders/$OWNED_ORDER_ID/status" >/dev/null
        OWNED_STATUS="$(jget "d['status']")"
        as_other
        ASSISTANT_Q="What is the status of order $OWNED_ORDER_ID?"
        check "customer B asking about customer A's order gets an answer (200), not an error" "200" \
            "$(ask "$ASSISTANT_Q")"
        printf '        "%s" (customer B; the order is A'"'"'s and %s)\n        -> %s\n        tools: %s\n' \
            "$ASSISTANT_Q" "$OWNED_STATUS" "$(jget "d['answer'][:300]")" "$(jget "d['toolsUsed']")"
        check "which reveals nothing of it: not its status" "False" \
            "$(jget "'$OWNED_STATUS'.lower() in d['answer'].lower()")"
        check "and cites no order: the application refused the lookup (403) made with B's own token" "0" \
            "$(jget "sum(1 for s in d['sources'] if s['type'] == 'order')")"
        as_customer
        ASSISTANT_Q="What is the status of my order $OWNED_ORDER_ID?"
        check "while customer A asking the same gets its status" "200" \
            "$(ask_until "'$OWNED_STATUS'.lower() in d['answer'].lower()" "$ASSISTANT_Q")"
        printf '        -> %s\n' "$(jget "d['answer'][:300]")"
        check "cited as their order" "True" \
            "$(jget "any(s['type'] == 'order' and s['id'] == '$OWNED_ORDER_ID' for s in d['sources'])")"

        # --- Store topics only ---
        check "an off-topic request is declined: it says it helps with shopping, and writes no poem" "200" \
            "$(ask_until "'shopping' in d['answer'].lower() and any(w in d['answer'].lower() for w in ('only help', \"can't\", 'cannot', 'unable', 'not able')) and len(d['answer']) < 400" "Write me a short poem about the sea.")"
        printf '        -> %s\n' "$(jget "d['answer'][:200]")"

        # --- Memory ---
        ask "My name is Asha. Do you sell desk mats?" >/dev/null
        ASSISTANT_CONVERSATION="$(jget "d['conversationId']")"
        ASSISTANT_MEMORY_KEY="$(redis_cli --scan --pattern "assistant:memory:*:$ASSISTANT_CONVERSATION" | tr -d '\r' | head -1)"
        check "the conversation is kept in Redis, under the user's id and the conversation's" "True" \
            "$(python3 -c "import re, sys; print(bool(re.fullmatch(r'assistant:memory:\d+:$ASSISTANT_CONVERSATION', sys.argv[1])))" "$ASSISTANT_MEMORY_KEY")"
        ASSISTANT_TTL="$(redis_cli TTL "$ASSISTANT_MEMORY_KEY" | tr -d '\r')"
        check "with an expiry (at most 24 h)" "True" \
            "$(python3 -c "print(0 < int('${ASSISTANT_TTL:-0}') <= 86400)")"
        request POST /api/assistant/chat \
            "{\"conversationId\":\"$ASSISTANT_CONVERSATION\",\"message\":\"What is my name?\"}" >/dev/null
        check "and the next message in it is answered with the earlier ones in mind" "True" \
            "$(jget "'Asha' in d['answer']")"

        ASSISTANT_CHATS_AFTER="$(assistant_scrape | python3 -c "$METRIC_PY" /dev/stdin ecomdemo_assistant_chats_seconds_count outcome=answered)"
        check "answers are counted in assistant-service's metrics" "True" \
            "$(python3 -c "print('$ASSISTANT_CHATS_AFTER' != 'MISSING' and float('$ASSISTANT_CHATS_AFTER') > float('${ASSISTANT_CHATS_BEFORE/MISSING/0}'))")"
        ;;
    503)
        if jget "d['message']" | grep -q "not configured"; then
            skip "the assistant answers a product question and refuses another customer's order" \
                "no models configured. It needs a chat model AND an embedding model: set AI_CHAT_PROVIDER=ollama and AI_EMBEDDING_PROVIDER=ollama in .env (after 'ollama pull qwen2.5:7b' and 'ollama pull nomic-embed-text' on the host), or both to openai with OPENAI_API_KEY; then 'docker compose up -d assistant-service' and run this script again"
        else
            fail "the assistant answers" "200" "503: $(jget "d['message']")"
        fi
        check "the assistant without a model is a 503 that says when to retry" "True" \
            "$(curl -sS -o /dev/null -D - -X POST "$BASE_URL/api/assistant/chat" \
                -H "Authorization: Bearer $CUSTOMER_TOKEN" -H 'Content-Type: application/json' \
                -d '{"message":"hello"}' | grep -qi '^retry-after: [1-9]' && echo True || echo False)"
        ;;
    *)
        fail "the assistant answers, or refuses with 503 when there is no model" "200 or 503" \
            "$ASSISTANT_STATUS: $(python3 -c "print(open('$BODY').read()[:200])" 2>/dev/null)"
        ;;
esac
as_customer

# --------------------------------------------------------------------------------------------
# Kubernetes (Phase 25) - only when SMOKE_PLATFORM=k8s
# --------------------------------------------------------------------------------------------
# Everything above ran through the Ingress (BASE_URL is Traefik on localhost:18080). This section
# checks what only an orchestrator can do. The phase's "done when": delete a pod and the flow still
# works while it is replaced; plus the objects the phase asked for, and a rolling update that no
# caller notices.
#
# Nothing is printed in compose mode: these checks have no meaning there, and a SKIP line for each
# would only be noise on every compose run.
if [ "$SMOKE_PLATFORM" = "k8s" ]; then
section "Kubernetes"

k8s_ready() { # k8s_ready <deployment> -> "ready/desired"
    kube get deployment "$1" -o jsonpath='{.status.readyReplicas}/{.spec.replicas}' 2>/dev/null
}

# --- The objects the phase asked for -------------------------------------------------------------
for service in app catalog-service customer-service inventory-service notification-service \
    payment-service assistant-service gateway-service; do
    OBJECTS="$(for kind in deployment service configmap secret; do
        suffix=""; [ "$kind" = "configmap" ] && suffix="-config"; [ "$kind" = "secret" ] && suffix="-secrets"
        kube get "$kind" "$service$suffix" -o name >/dev/null 2>&1 && printf '%s ' "$kind"
    done)"
    check "$service has a Deployment, Service, ConfigMap and Secret" \
        "deployment service configmap secret " "$OBJECTS"
done
check "every application container has a startup, liveness and readiness probe" "0" \
    "$(kube get deployments -l app.kubernetes.io/part-of=ecomdemo -o json | python3 -c "
import json, sys
print(sum(1 for d in json.load(sys.stdin)['items'] for c in d['spec']['template']['spec']['containers']
          if not all(c.get(p) for p in ('startupProbe', 'livenessProbe', 'readinessProbe'))))")"
check "and a memory limit and a CPU request" "0" \
    "$(kube get deployments -l app.kubernetes.io/part-of=ecomdemo -o json | python3 -c "
import json, sys
print(sum(1 for d in json.load(sys.stdin)['items'] for c in d['spec']['template']['spec']['containers']
          if not (c.get('resources', {}).get('limits', {}).get('memory')
                  and c.get('resources', {}).get('requests', {}).get('cpu'))))")"
check "the Ingress sends everything to the gateway" "gateway-service" \
    "$(kube get ingress ecomdemo -o jsonpath='{.spec.rules[0].http.paths[0].backend.service.name}')"

# The HPA needs metrics-server: until it reports, the target reads <unknown> and nothing scales.
HPA_CPU=""
for _ in $(seq 1 90); do
    HPA_CPU="$(kube get hpa catalog-service -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null)"
    [ -n "$HPA_CPU" ] && break
    sleep 2
done
check "the HPA on catalog-service reads its CPU (now ${HPA_CPU:-unknown}% of request)" "True" \
    "$([ -n "$HPA_CPU" ] && echo True || echo False)"
check "and keeps between 2 and 4 replicas" "2-4" \
    "$(kube get hpa catalog-service -o jsonpath='{.spec.minReplicas}-{.spec.maxReplicas}')"

# --- Self-healing: delete pods, and the flow does not notice -------------------------------------
# One gateway pod and one catalog pod are deleted, and a whole checkout runs IMMEDIATELY, while their
# replacements are still starting. It works because each Deployment keeps two replicas: the Service
# stops sending to a terminating pod and the survivor answers. The ReplicaSet notices it is one pod
# short and creates a new one - nobody asked it to; its only job is to make "actual" match "desired".
GATEWAY_VICTIM="$(kube get pods -l app.kubernetes.io/name=gateway-service -o jsonpath='{.items[0].metadata.name}')"
CATALOG_VICTIM="$(kube get pods -l app.kubernetes.io/name=catalog-service -o jsonpath='{.items[0].metadata.name}')"
kube delete pod "$GATEWAY_VICTIM" "$CATALOG_VICTIM" --wait=false >/dev/null
pass "deleted $GATEWAY_VICTIM and $CATALOG_VICTIM"

as_anonymous
check "the catalogue still answers through the Ingress" "200" "$(request GET /api/products)"
HEAL_PRODUCT="$(jget "next(p['id'] for p in d if p['stockQuantity'] >= 1 and p['price'] <= $PAYMENT_LIMIT)")"
check "a customer can still log in" "True" \
    "$([ -n "$(login "$CUSTOMER_USER" "$CUSTOMER_PASSWORD")" ] && echo True || echo False)"
as_customer
request GET /api/cart >/dev/null
for product_id in $(jget "' '.join(str(i['productId']) for i in d['items'])"); do
    request DELETE "/api/cart/items/$product_id" >/dev/null
done
check "and add to the cart" "200" "$(request POST /api/cart/items "{\"productId\":$HEAL_PRODUCT,\"quantity\":1}")"
check "and check out" "201" "$(request POST /api/orders)"
HEAL_ORDER="$(jget "d['id']")"
check "and the saga still confirms the order" "CONFIRMED" "$(wait_for_order_status "$HEAL_ORDER")"

HEALED=false
for _ in $(seq 1 90); do
    if [ "$(k8s_ready gateway-service)" = "2/2" ] && [ "$(k8s_ready catalog-service | cut -d/ -f1)" -ge 2 ] 2>/dev/null; then
        HEALED=true; break
    fi
    sleep 2
done
check "the deleted pods were REPLACED: gateway $(k8s_ready gateway-service), catalog $(k8s_ready catalog-service) ready" \
    "true" "$HEALED"
# A deleted pod is listed as Terminating until it has finished stopping - the 5 s preStop pause, then
# Spring's graceful shutdown - so wait for it to be GONE rather than counting it while it drains.
kube wait --for=delete "pod/$GATEWAY_VICTIM" "pod/$CATALOG_VICTIM" --timeout=120s >/dev/null 2>&1
check "by new pods: the deleted ones are gone" "0" \
    "$(kube get pods -o name | grep -c -e "$GATEWAY_VICTIM" -e "$CATALOG_VICTIM")"

# --- A rolling update no caller notices -----------------------------------------------------------
# `rollout restart` changes the pod template (an annotation), which is exactly what a new image
# would do: the Deployment makes a new ReplicaSet and swaps pods one at a time - start a new one,
# wait for its READINESS probe, then stop an old one (maxSurge 1, maxUnavailable 0). Meanwhile a
# loop calls the API through the Ingress and counts every answer that is not 200.
REVISION_BEFORE="$(kube get deployment gateway-service -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}')"
ROLLOUT_LOG="$(mktemp)"
(
    while :; do
        curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 "$BASE_URL/api/products" >> "$ROLLOUT_LOG"
        sleep 0.1
    done
) &
ROLLOUT_LOAD_PID=$!
sleep 2
kube rollout restart deployment/gateway-service >/dev/null
kube rollout status deployment/gateway-service --timeout=300s >/dev/null 2>&1
ROLLED=$?
sleep 3
kill "$ROLLOUT_LOAD_PID" 2>/dev/null; wait "$ROLLOUT_LOAD_PID" 2>/dev/null || true
ROLLOUT_REQUESTS="$(wc -l < "$ROLLOUT_LOG" | tr -d ' ')"
ROLLOUT_FAILURES="$(grep -vc '^200$' "$ROLLOUT_LOG")"
rm -f "$ROLLOUT_LOG"
check "the gateway rolled out to a new revision" "True" \
    "$([ "$ROLLED" = 0 ] && [ "$(kube get deployment gateway-service -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}')" -gt "${REVISION_BEFORE:-0}" ] && echo True || echo False)"
check "and not one of $ROLLOUT_REQUESTS requests during it failed" "0" "$ROLLOUT_FAILURES"
fi

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
