#!/usr/bin/env bash
#
# Regression test for KI-043: smoke-test.sh must refuse to run against a compose stack that belongs
# to ANOTHER checkout (another clone shares the fixed container names and ports), before it sends a
# single request or stops a single container.
#
# It needs no running stack: `docker` and `curl` are replaced by fakes that log every call.
#   scripts/test-smoke-guard.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
WORK="$(mktemp -d)"
trap '[ -n "${KEEP:-}" ] && cp "$WORK/last.log" "$KEEP"; rm -rf "$WORK"' EXIT
mkdir "$WORK/bin"
CALLS="$WORK/calls.log"
: > "$CALLS"

# Fake docker: `inspect` reports the working dir in $FAKE_WORKDIR; anything else is just logged.
cat > "$WORK/bin/docker" <<'F'
#!/usr/bin/env bash
echo "docker $*" >> "$CALLS"
if [ "$1" = "inspect" ]; then printf '%s\n' "$FAKE_WORKDIR"; exit 0; fi
exit 0
F
cat > "$WORK/bin/curl" <<'F'
#!/usr/bin/env bash
echo "curl $*" >> "$CALLS"
exit 7
F
chmod +x "$WORK/bin/docker" "$WORK/bin/curl"
export CALLS

FAILURES=0
check() { # check <name> <ok:0|1>
    if [ "$2" -eq 0 ]; then printf '  PASS  %s\n' "$1"; else printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); fi
}

run_smoke() { # run_smoke <fake workdir> [env...]  -> sets RC and OUT
    : > "$CALLS"
    local wd="$1"; shift
    OUT="$(env PATH="$WORK/bin:$PATH" FAKE_WORKDIR="$wd" SMOKE_STATE_FILE="$WORK/state" "$@" \
        timeout 60 "$ROOT/scripts/smoke-test.sh" 2>&1)"
    RC=$?; cp "$CALLS" "$WORK/last.log" 2>/dev/null
}

echo "KI-043 smoke-test stack guard"

run_smoke "/home/someone/projects/ecomdemo-backend-readonly"
check "foreign stack: exits 2" "$([ "$RC" -eq 2 ]; echo $?)"
check "foreign stack: says why" "$(printf '%s' "$OUT" | command grep -q "another checkout"; echo $?)"
check "foreign stack: no HTTP request sent" "$(! command grep -q '^curl' "$CALLS"; echo $?)"
check "foreign stack: no container exec/stop/start" "$(! command grep -Eq '^docker (exec|stop|start)' "$CALLS"; echo $?)"
command grep -E '^docker (exec|stop|start)' "$CALLS" | head -3 | sed 's/^/        saw: /'
command grep -E '^docker (exec|stop|start)' "$CALLS" | head -3 | sed 's/^/        saw: /'

run_smoke "/home/someone/projects/ecomdemo-backend-readonly" SMOKE_ALLOW_FOREIGN_STACK=1
check "override: the guard lets the run continue" "$(! printf '%s' "$OUT" | command grep -q "another checkout"; echo $?)"

run_smoke "$ROOT"
check "own stack: the guard lets the run continue" "$(! printf '%s' "$OUT" | command grep -q "another checkout"; echo $?)"

[ "$FAILURES" -eq 0 ] && echo "all passed" || { echo "$FAILURES failed"; exit 1; }
