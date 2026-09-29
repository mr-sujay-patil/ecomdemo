#!/usr/bin/env bash
#
# The same load against the stack set up several ways, one variable at a time.
#
#   Usage:  scripts/perf-compare.sh <cache|app-pool> [browse|checkout|mixed] [ramp|steady|spike]
#
#   cache     catalog-service with its Redis cache on (today) and off     default: browse steady
#   app-pool  ecomdemo-app's Hikari pool at 2, 5, 10 (today) and 30        default: checkout steady
#
# For each setting it recreates only the service concerned with the new environment (compose.yaml
# reads CATALOG_CACHE_TYPE, APP_DB_POOL_SIZE; their defaults are today's values), restarts the
# gateway (it keeps a recreated service's OLD address otherwise - see RECENT.md, Phase 29), waits
# for health, and runs scripts/perf-test.sh with a label naming the setting. At the end the
# service is put back to its defaults and the labelled lines are printed side by side.
#
# PERF_* knobs pass through to every run (PERF_RATE, PERF_DURATION_SECONDS, ...). Runs do not stop
# the comparison when an assertion fails: a configuration that falls over IS a result.

set -uo pipefail

EXPERIMENT="${1:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

case "$EXPERIMENT" in
    cache)
        SERVICE=catalog-service; VARIABLE=CATALOG_CACHE_TYPE; VALUES="redis none"
        SIMULATION="${2:-browse}"; PROFILE="${3:-steady}" ;;
    app-pool)
        SERVICE=app; VARIABLE=APP_DB_POOL_SIZE; VALUES="2 5 10 30"
        SIMULATION="${2:-checkout}"; PROFILE="${3:-steady}" ;;
    *)
        echo "usage: $0 <cache|app-pool> [browse|checkout|mixed] [ramp|steady|spike]" >&2
        exit 2 ;;
esac

# A run that trips the (deliberately generous) assertions still has numbers worth keeping.
export PERF_MAX_FAILED_PERCENT="${PERF_MAX_FAILED_PERCENT:-100}"
export PERF_MAX_P95_MS="${PERF_MAX_P95_MS:-100000}"

wait_healthy() {
    for _ in $(seq 1 60); do
        if ! docker compose ps --format '{{.Health}}' | command grep -q -E 'starting|unhealthy'; then
            return 0
        fi
        sleep 5
    done
    echo "stack not healthy after 5 minutes" >&2
    return 1
}

reconfigure() {
    # Recreate ONLY this service (--no-deps), with the variable set or, for "", unset.
    if [ -n "$1" ]; then
        env "$VARIABLE=$1" docker compose up -d --no-deps --force-recreate "$SERVICE" >/dev/null 2>&1
    else
        env -u "$VARIABLE" docker compose up -d --no-deps --force-recreate "$SERVICE" >/dev/null 2>&1
    fi
    docker compose restart gateway-service >/dev/null 2>&1
    wait_healthy
}

LABELS=()
for value in $VALUES; do
    label="$EXPERIMENT=$value"
    echo
    echo "#### $SERVICE with $VARIABLE=$value"
    reconfigure "$value" || exit 1
    PERF_LABEL="$label" scripts/perf-test.sh "$SIMULATION" "$PROFILE"
    LABELS+=("$label")
done

echo
echo "#### restoring $SERVICE to its defaults"
reconfigure "" || exit 1

echo
echo "#### $EXPERIMENT: $SIMULATION / $PROFILE, rate ${PERF_RATE:-20}/s"
python3 - "$ROOT/performance-tests/target/perf-results.tsv" "${LABELS[@]}" <<'PY'
import csv, sys
path, labels = sys.argv[1], sys.argv[2:]
rows = list(csv.DictReader(open(path, encoding="utf-8"), delimiter="\t"))
latest = {}
for row in rows:                        # the last run of each label wins
    if row["label"] in labels:
        latest[(row["label"], row["request"])] = row
requests = []
for (_, request) in latest:
    if request not in requests:
        requests.append(request)
print(f"{'request':<34}{'setting':<22}{'total':>8}{'KO':>6}{'req/s':>9}{'p50':>7}{'p95':>7}{'p99':>7}{'max':>7}")
for request in requests:
    for label in labels:
        r = latest.get((label, request))
        if r:
            print(f"{request:<34}{label:<22}{r['total']:>8}{r['ko']:>6}{r['rps']:>9}"
                  f"{r['p50']:>7}{r['p95']:>7}{r['p99']:>7}{r['max']:>7}")
PY
