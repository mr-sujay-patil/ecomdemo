#!/usr/bin/env bash
#
# Regression test for KI-082: a smoke check that pipes into `grep -q` can fail with the match present.
# smoke-test.sh runs under `set -o pipefail`. `grep -q` exits at its first match; a producer still writing
# then gets SIGPIPE (curl: "(23) Failed writing headers"), and pipefail makes the pipeline report the
# producer's failure, so `... | grep -q X && echo True || echo False` printed False. `contains`
# (scripts/smoke-pipes.sh) matches like grep but reads all of its input, so the producer always finishes.
# Needs no stack.
#   scripts/test-smoke-pipes.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
# shellcheck source=scripts/smoke-pipes.sh
. "$ROOT/scripts/smoke-pipes.sh"

FAILURES=0
# expect <name> <expected> <actual>
expect() {
    if [ "$2" = "$3" ]; then
        printf '  PASS  %s\n' "$1"
    else
        printf '  FAIL  %s: got %s, want %s\n' "$1" "$3" "$2"
        FAILURES=$((FAILURES + 1))
    fi
}

# A response whose Retry-After comes FIRST and is followed by far more than a pipe buffer (64 KiB) of
# headers: after grep's match the producer is certainly still writing. A real response is smaller and
# loses the race only sometimes (PR #112, run 41850018946); this makes it lose every time.
headers() {
    printf 'HTTP/1.1 503 Service Unavailable\r\nRetry-After: 10\r\n'
    for i in $(seq 1 20000); do printf 'X-Padding-%05d: aaaaaaaaaaaaaaaaaaaa\r\n' "$i"; done
}

# The defect, reproduced: the header is there, and the old form still says it is not.
expect "the old form (grep -q under pipefail) loses the race against a producer still writing" "False" \
    "$(headers | grep -qi '^retry-after: [1-9]' && echo True || echo False)"

# The fix: the same check through contains.
expect "contains finds the header and the pipeline succeeds" "True" \
    "$(headers | contains -i '^retry-after: [1-9]' && echo True || echo False)"
expect "contains still says no when there is no match" "False" \
    "$(headers | contains -i '^x-not-there:' && echo True || echo False)"
expect "contains passes grep's flags on (-x, a whole-line match)" "True" \
    "$(printf 'a\nPONG\nb\n' | contains -x PONG && echo True || echo False)"
expect "and a producer that FAILS still fails the pipeline: pipefail keeps its job" "False" \
    "$({ echo PONG; exit 3; } | contains PONG && echo True || echo False)"

# The guard: no check in the smoke test pipes into grep -q any more (a grep -q on a FILE is fine).
PIPED="$(grep -nE '\|[[:space:]]*grep[[:space:]]+-q' "$ROOT/scripts/smoke-test.sh" || true)"
expect "smoke-test.sh pipes into contains, never into grep -q" "" "$PIPED"
expect "and it sources smoke-pipes.sh" "True" \
    "$(grep -q 'smoke-pipes.sh' "$ROOT/scripts/smoke-test.sh" && echo True || echo False)"

if [ "$FAILURES" -gt 0 ]; then
    echo "test-smoke-pipes: $FAILURES failure(s)"
    exit 1
fi
echo "test-smoke-pipes: all passed"
