#!/usr/bin/env bash
#
# Regression test for KI-064 and KI-066: which Redis and PostgreSQL client the smoke test's helpers
# (scripts/smoke-clients.sh) reach. On compose a client on the host is preferred (it is the stack's own,
# on 127.0.0.1); on the kind cluster only the in-pod path reaches the cluster's Redis and databases
# (nothing forwards 6379 or 5432, and they need TLS with a client certificate), and that path needs
# kubectl, not docker. Needs no stack: the clients, docker and ctr_exec are fakes that log the call.
#   scripts/test-smoke-clients.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CALLS="$WORK/calls.log"
export CALLS

# A PATH with only what the helpers use besides the clients, so "is there a redis-cli/psql/docker on
# this machine" is decided by the test, not by the machine it runs on.
mkdir "$WORK/sys" "$WORK/host-clients" "$WORK/docker"
for tool in sed tail sh cat; do ln -s "$(command -v "$tool")" "$WORK/sys/$tool"; done
for tool in redis-cli psql; do
    printf '#!/bin/sh\necho "host %s" >> "$CALLS"\n' "$tool" > "$WORK/host-clients/$tool"
done
printf '#!/bin/sh\necho "docker $*" >> "$CALLS"\n' > "$WORK/docker/docker"
chmod +x "$WORK"/host-clients/* "$WORK/docker/docker"

# The smoke test's ctr_exec, faked: it records that the in-pod (or in-container) path was taken.
ctr_exec() { echo "pod $1" >> "$CALLS"; }
REDIS_CONTAINER=ecomdemo-cache POSTGRES_CONTAINER=ecomdemo-db REDIS_TLS="" PGUSER_=ecomdemo PGDB=ecomdemo
# shellcheck source=scripts/smoke-clients.sh
. "$ROOT/scripts/smoke-clients.sh"

FAILURES=0
# reaches <name> <expected first line of the log> <platform> <path dirs> <helper and args...>
reaches() {
    local name="$1" want="$2" platform="$3" dirs="$4"; shift 4
    : > "$CALLS"
    ( PATH="$dirs:$WORK/sys" SMOKE_PLATFORM="$platform" "$@" ) >/dev/null 2>&1
    local got; got="$(grep -v '^pod .* true$' "$CALLS" | head -1)"
    [ -z "$got" ] && got="$(head -1 "$CALLS")"
    if [ "$got" = "$want" ]; then printf '  PASS  %s\n' "$name"
    else printf '  FAIL  %s: reached "%s", want "%s"\n' "$name" "$got" "$want"; FAILURES=$((FAILURES + 1)); fi
}
HOST="$WORK/host-clients"; DOCKER="$WORK/docker"; NONE="$WORK/none"

# Compose: unchanged. A host client wins; without one, the container.
reaches "compose, host redis-cli: redis_cli uses it"         "host redis-cli" compose "$HOST:$DOCKER" redis_cli PING
reaches "compose, host redis-cli: redis_cli_noauth uses it"  "host redis-cli" compose "$HOST:$DOCKER" redis_cli_noauth PING
reaches "compose, host psql: psql_query uses it"              "host psql"      compose "$HOST:$DOCKER" psql_query "select 1"
reaches "compose, no host clients: redis_cli uses the container" "pod ecomdemo-cache" compose "$DOCKER" redis_cli PING
reaches "compose, no host clients: psql_query uses the container" "pod ecomdemo-db"   compose "$DOCKER" psql_query "select 1"
# k8s: always the pod, even with host clients installed (KI-064, KI-066).
reaches "k8s, host redis-cli installed: redis_cli uses the pod"        "pod ecomdemo-cache" k8s "$HOST:$DOCKER" redis_cli PING
reaches "k8s, host redis-cli installed: redis_cli_noauth uses the pod" "pod ecomdemo-cache" k8s "$HOST:$DOCKER" redis_cli_noauth PING
reaches "k8s, host psql installed: psql_query uses the pod"            "pod ecomdemo-db"    k8s "$HOST:$DOCKER" psql_query "select 1"
# k8s with no docker CLI at all (kubectl is what ctr_exec uses there).
reaches "k8s, no docker CLI: redis_cli still uses the pod"   "pod ecomdemo-cache" k8s "$NONE" redis_cli PING
reaches "k8s, no docker CLI: psql_query still uses the pod"  "pod ecomdemo-db"    k8s "$NONE" psql_query "select 1"

if [ "$FAILURES" -gt 0 ]; then echo "test-smoke-clients: $FAILURES failed"; exit 1; fi
echo "test-smoke-clients: all passed"
