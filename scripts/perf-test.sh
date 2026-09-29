#!/usr/bin/env bash
#
# Runs one Gatling simulation against the running stack and prints a one-screen summary.
#
#   Usage:  scripts/perf-test.sh <browse|checkout|mixed> [ramp|steady|spike]
#
#   Knobs (environment, all optional; see performance-tests/.../PerfConfig.java):
#     PERF_RATE=20               sessions arriving per second at the plateau
#     PERF_DURATION_SECONDS=60   plateau (steady) or climb (ramp) length
#     PERF_SPIKE_USERS=300       sessions in the 10 s spike
#     PERF_USERS=200             customer accounts shared by the sessions
#     PERF_CHECKOUT_SHARE=0.2    mixed only: share of sessions that check out
#     PERF_LABEL=baseline        a name for this run in the results file
#
# Every run appends one line per request type to performance-tests/target/perf-results.tsv,
# tagged with PERF_LABEL, so a comparison is: set the stack up one way, run with one label, set it
# up the other way, run with another, and read the two blocks side by side. The full HTML report
# is under performance-tests/target/gatling/.
#
# Exits non-zero when the simulation's assertions fail (error rate, p95), so a run can gate.
#
# Nothing here changes the stack. scripts/perf-compare.sh is the one that restarts services with
# different settings.

set -uo pipefail

SIMULATION="${1:-}"
PROFILE="${2:-steady}"
case "$SIMULATION" in
    browse) CLASS=BrowseSimulation ;;
    checkout) CLASS=CheckoutSimulation ;;
    mixed) CLASS=MixedSimulation ;;
    *) echo "usage: $0 <browse|checkout|mixed> [ramp|steady|spike]" >&2; exit 2 ;;
esac
case "$PROFILE" in
    ramp|steady|spike) ;;
    *) echo "profile must be ramp, steady or spike" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/performance-tests/target"
LABEL="${PERF_LABEL:-$SIMULATION-$PROFILE}"
mkdir -p "$TARGET"
LOG="$TARGET/perf-last-run.log"

echo "== $SIMULATION / $PROFILE / label '$LABEL' (rate ${PERF_RATE:-20}/s, ${PERF_DURATION_SECONDS:-60} s)"
PERF_PROFILE="$PROFILE" "$ROOT/mvnw" -q -f "$ROOT/performance-tests/pom.xml" gatling:test \
    -Dgatling.simulationClass="com.ecomdemo.perf.$CLASS" > "$LOG" 2>&1
STATUS=$?

REPORT="$(ls -td "$TARGET"/gatling/"${CLASS,,}"-*/ 2>/dev/null | head -1)"
if [ -z "$REPORT" ] || [ ! -f "$REPORT/index.html" ]; then
    echo "no report produced; last lines of $LOG:" >&2
    tail -20 "$LOG" >&2
    exit 1
fi

command grep -E '^\[perf' "$LOG"

# The statistics table of the HTML report, one row per request type. Gatling 3.15 no longer
# writes a stats.json; the table is the machine-readable part that is left. Columns, in order:
# total, OK, KO, %KO, req/s, min, p50, p75, p95, p99, max, mean, std dev.
python3 - "$REPORT/index.html" "$LABEL" "$PROFILE" "${PERF_RATE:-20}" "$TARGET/perf-results.tsv" <<'PY'
import html, os, re, sys
path, label, profile, rate, results = sys.argv[1:]
text = re.sub(r"<[^>]+>", "|", open(path, encoding="utf-8").read())
start = text.find("All Requests")
cells = [html.unescape(c).strip() for c in text[start:].split("|")]
cells = [c for c in cells if c and c != "\xa0"]
number = re.compile(r"^-?\d+(\.\d+)?$|^-$")
rows, i = [], 0
while i < len(cells):
    name = cells[i]
    values = []
    j = i + 1
    while j < len(cells) and number.match(cells[j]) and len(values) < 14:
        values.append(cells[j]); j += 1
    # The first number after a name is the table's own nesting level; the 13 after it are stats.
    if len(values) == 14:
        rows.append((name, values[1:]))
        i = j
    elif rows:
        break
    else:
        i += 1
new_file = not os.path.exists(results)
with open(results, "a", encoding="utf-8") as out:
    if new_file:
        out.write("label\tprofile\trate\trequest\ttotal\tko\trps\tp50\tp95\tp99\tmax\tmean\n")
    print(f"{'request':<45}{'total':>8}{'KO':>6}{'req/s':>9}{'p50':>7}{'p95':>7}{'p99':>7}{'max':>7}")
    for name, v in rows:
        total, ok, ko, pct_ko, rps, mn, p50, p75, p95, p99, mx, mean, std = v
        print(f"{name:<45}{total:>8}{ko:>6}{rps:>9}{p50:>7}{p95:>7}{p99:>7}{mx:>7}")
        out.write("\t".join([label, profile, rate, name, total, ko, rps, p50, p95, p99, mx, mean]) + "\n")
PY

command grep -E ': (true|false)' "$LOG" | sed 's/^/assert: /'
echo "report: ${REPORT}index.html"
exit $STATUS
