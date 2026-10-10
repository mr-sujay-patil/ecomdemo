#!/usr/bin/env bash
#
# Regression test for KI-055: the smoke test's verdict on the rate-limit burst (scripts/smoke-burst.sh).
# Fed with status distributions that real CI runs printed (smoke.yml, 2026-10-08 to 2026-10-10), a cold
# run like the one that failed, and the two outcomes the checks exist to catch. Needs no stack.
#   scripts/test-smoke-burst.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
# shellcheck source=scripts/smoke-burst.sh
. "$ROOT/scripts/smoke-burst.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILURES=0
# burst <name> <expected limited> <expected throttles> <count> <status> [<count> <status> ...]
burst() {
    local name="$1" want_limited="$2" want_throttles="$3"; shift 3
    local file="$WORK/burst"; : > "$file"
    while [ "$#" -gt 0 ]; do
        for _ in $(seq 1 "$1"); do echo "$2" >> "$file"; done
        shift 2
    done
    local limited throttles
    limited="$(burst_limited "$file")"; throttles="$(burst_throttles "$file")"
    if [ "$limited" = "$want_limited" ] && [ "$throttles" = "$want_throttles" ]; then
        printf '  PASS  %s\n' "$name"
    else
        printf '  FAIL  %s: limited %s (want %s), throttles %s (want %s)\n' \
            "$name" "$limited" "$want_limited" "$throttles" "$want_throttles"
        FAILURES=$((FAILURES + 1))
    fi
}

# What CI printed (smoke.yml runs 2 to 7). Every one is a limiter that throttles.
burst "run 2a1: 97 x 200  200 x 429  3 x 503"  True True 97 200 200 429 3 503
burst "run 2a2: 144 x 200  150 x 429  6 x 503" True True 144 200 150 429 6 503
burst "run 2a3: 150 x 200  150 x 429"          True True 150 200 150 429
burst "run 3: 111 x 200  150 x 429  39 x 503"  True True 111 200 150 429 39 503
burst "run 5: 146 x 200  150 x 429  4 x 503"   True True 146 200 150 429 4 503
burst "run 7: 129 x 200  170 x 429  1 x 503"   True True 129 200 170 429 1 503
# Run 1 (failed, counts not printed yet): the limiter admitted ~150 as always, but on a cold runner most
# of them ended as the catalog route's fallback 503. The limiter did not ban, so the check must pass.
burst "cold runner: 30 x 200  150 x 429  120 x 503" True True 30 200 150 429 120 503
# What the checks exist to catch.
burst "a ban: 10 x 200  290 x 429"             True False 10 200 290 429
burst "no limiter: 300 x 200"                  False True 300 200
# No answer at all (curl's 000) is not an admitted request.
burst "unreachable: 150 x 429  150 x 000"      True False 150 429 150 000

if [ "$FAILURES" -gt 0 ]; then echo "test-smoke-burst: $FAILURES failed"; exit 1; fi
echo "test-smoke-burst: all passed"
