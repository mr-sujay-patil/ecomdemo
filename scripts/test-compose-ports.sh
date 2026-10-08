#!/usr/bin/env bash
#
# Regression test for KI-003: compose must publish every port on the loopback interface only, so
# the databases (default passwords), Redis and Kafka (no authentication) are not reachable from
# the network the machine is on. Setting BIND_ADDRESS=0.0.0.0 is the explicit way to opt out.
#
# It needs no running stack: it renders the compose files (every profile) and reads the result.
#   scripts/test-compose-ports.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT" || exit 2
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# One "service host_ip published" line per published port.
cat > "$WORK/ports.py" <<'PY'
import json, sys
for name, service in json.load(sys.stdin).get("services", {}).items():
    for port in service.get("ports", []):
        print(name, port.get("host_ip", "ALL-INTERFACES"), port.get("published"))
PY

render() { # render <compose file> -> lines on stdout; BIND_ADDRESS comes from the caller
    docker compose -f "$1" --profile '*' config --format json 2>"$WORK/err.log" \
        | python3 -I "$WORK/ports.py"
}

FAILURES=0
check() { # check <name> <ok:0|1> [detail]
    if [ "$2" -eq 0 ]; then
        printf '  PASS  %s\n' "$1"
    else
        printf '  FAIL  %s\n' "$1"; [ -n "${3:-}" ] && printf '        %s\n' "$3"
        FAILURES=$((FAILURES + 1))
    fi
}

for file in compose.yaml compose.sonar.yaml; do
    # An empty BIND_ADDRESS is "unset" to compose's ${VAR:-default}, and the environment beats .env.
    ports="$(BIND_ADDRESS='' render "$file")"
    total="$(printf '%s\n' "$ports" | grep -c .)"
    exposed="$(printf '%s\n' "$ports" | grep -v ' 127\.0\.0\.1 ' | grep . || true)"
    check "$file publishes ports (so this test is looking at something)" "$([ "$total" -gt 0 ]; echo $?)"
    check "$file: every one of its $total published ports is bound to 127.0.0.1" \
        "$([ -z "$exposed" ] && [ "$total" -gt 0 ]; echo $?)" \
        "$(printf '%s' "$exposed" | head -3 | tr '\n' ';')"

    opened="$(BIND_ADDRESS=0.0.0.0 render "$file" | grep -vc ' 0\.0\.0\.0 ' || true)"
    check "$file: BIND_ADDRESS=0.0.0.0 opts every port out" "$([ "$opened" -eq 0 ] && [ "$total" -gt 0 ]; echo $?)"
done

if [ "$FAILURES" -eq 0 ]; then printf '\nAll checks passed.\n'; else printf '\n%d check(s) FAILED.\n' "$FAILURES"; fi
[ "$FAILURES" -eq 0 ]
