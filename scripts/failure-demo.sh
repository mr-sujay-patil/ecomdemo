#!/usr/bin/env bash
# ============================================================================================
# Failure demo (Phase 22): stop catalog-service and watch the application degrade, then recover.
# ============================================================================================
#
# Run it against a running stack (`docker compose up --build --wait`), ideally with the
# "EcomDemo Resilience" dashboard open beside it:
#
#     http://localhost:3000/d/ecomdemo-resilience
#
#     ./scripts/failure-demo.sh
#
# What it shows, one step at a time:
#   1. catalog-service UP: adding to a cart takes a few milliseconds.
#   2. catalog-service STOPPED: the first requests fail with 503 in about a second - two attempts,
#      each waiting out a timeout, because a stopped container hangs rather than refuses - and
#      after five failed calls the circuit breaker OPENS.
#   3. With the circuit OPEN, requests are refused in microseconds without touching the network.
#   4. Checkout of a cart that was filled earlier still works: it never needed the catalogue.
#   5. catalog-service STARTED again: after its JVM starts and the breaker's 10 s wait, trial calls
#      go through in HALF_OPEN, and the breaker CLOSES. Nobody restarted the application.
#
# It is not a test - scripts/smoke-test.sh asserts the same things - it is the version meant to be
# watched. It registers a throwaway customer with a random password for itself, and restarts
# catalog-service on exit even if interrupted.
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"   # the gateway: what a client uses
APP_URL="${APP_URL:-http://localhost:8084}"     # the application: only to read its circuit state
CATALOG_CONTAINER="${CATALOG_CONTAINER:-ecomdemo-catalog-service}"
BODY="$(mktemp)"
STOPPED=false

cleanup() {
    rm -f "$BODY"
    if [ "$STOPPED" = true ]; then
        printf '\n\033[33mrestarting %s, which this demo stopped\033[0m\n' "$CATALOG_CONTAINER"
        docker start "$CATALOG_CONTAINER" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

bold() { printf '\n\033[1m%s\033[0m\n' "$1"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# state -> the breaker's current state, read from the application's Prometheus scrape.
state() {
    curl -sS "$APP_URL/actuator/prometheus" | python3 -c '
import re, sys
for line in sys.stdin:
    m = re.match(r"resilience4j_circuitbreaker_state\{(.*)\}\s+1\.0$", line.strip())
    if m and "name=\"catalog\"" in m.group(1):
        print(re.search(r"state=\"([a-z_]+)\"", m.group(1)).group(1).upper())
        break
else:
    print("UNKNOWN")'
}

# call <method> <path> [data] -> prints "<status> <ms>", body in $BODY.
call() {
    local started status
    started="$(now_ms)"
    status="$(curl -sS -o "$BODY" -w '%{http_code}' -X "$1" "$BASE_URL$2" \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' ${3:+-d "$3"})"
    echo "$status $(( $(now_ms) - started ))"
}

# show <label> <status ms> -> one formatted line, with the circuit state after the call.
show() {
    local status="${2% *}" ms="${2#* }" colour='32'
    [ "$status" -ge 500 ] && colour='31'
    printf '  %-34s \033[%sm%s\033[0m  %5s ms   circuit: %-9s %s\n' "$1" "$colour" "$status" "$ms" \
        "$(state)" "$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d.get("message","") if isinstance(d,dict) else "")' "$BODY" 2>/dev/null)"
}

# --- A customer of our own -------------------------------------------------------------------
DEMO_USER="demo-$(date +%s)"
PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')"
curl -sS -o /dev/null -X POST "$BASE_URL/api/customers/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$DEMO_USER\",\"password\":\"$PASSWORD\",\"fullName\":\"Failure Demo\"}"
TOKEN="$(curl -sS -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$DEMO_USER\",\"password\":\"$PASSWORD\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')"
PRODUCT_ID="$(curl -sS "$BASE_URL/api/products" \
    | python3 -c 'import json,sys; print(next(p["id"] for p in json.load(sys.stdin) if p["stockQuantity"] >= 3))')"
ADD="{\"productId\":$PRODUCT_ID,\"quantity\":1}"

bold "1. catalog-service is UP"
show "add to cart" "$(call POST /api/cart/items "$ADD")"
show "add to cart" "$(call POST /api/cart/items "$ADD")"

bold "2. docker stop $CATALOG_CONTAINER"
docker stop "$CATALOG_CONTAINER" >/dev/null
STOPPED=true
for i in 1 2 3 4; do
    show "add to cart (attempt $i)" "$(call POST /api/cart/items "$ADD")"
done

bold "3. the circuit is OPEN: refused without calling catalog-service"
for i in 1 2 3; do
    show "add to cart" "$(call POST /api/cart/items "$ADD")"
done

bold "4. what does not need the catalogue still works"
show "checkout (cart filled in step 1)" "$(call POST /api/orders)"
show "order history" "$(call GET /api/orders)"

bold "5. docker start $CATALOG_CONTAINER - and nobody touches the application"
docker start "$CATALOG_CONTAINER" >/dev/null
STOPPED=false
STARTED="$(date +%s)"
while :; do
    RESULT="$(call POST /api/cart/items "$ADD")"
    show "add to cart (+$(( $(date +%s) - STARTED ))s)" "$RESULT"
    [ "${RESULT% *}" = "200" ] && [ "$(state)" = "CLOSED" ] && break
    [ $(( $(date +%s) - STARTED )) -gt 120 ] && { echo "  gave up after 120 s"; exit 1; }
    sleep 3
done

bold "Recovered. The whole story is on http://localhost:3000/d/ecomdemo-resilience"
