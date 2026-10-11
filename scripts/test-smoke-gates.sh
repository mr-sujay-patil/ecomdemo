#!/usr/bin/env bash
#
# Regression test for KI-067: the smoke test decides whether a section can run by asking the container
# (compose) or the pod (k8s) itself, through ctr_exec, never by looking for a docker CLI. On the kind
# cluster ctr_exec uses kubectl, so a `command -v docker` in front of it made the outbox, Kafka and saga
# sections SKIP on a machine with kubectl but no docker (kind on podman, or a cluster created elsewhere).
#
# It runs the REAL gate conditions, read out of scripts/smoke-test.sh, with the real ctr_exec, kube and
# k8s_workload from that file and the real scripts/smoke-clients.sh, on a PATH the test controls; only
# kubectl and docker are fakes, which log their whole argument list. Needs no stack.
#   scripts/test-smoke-gates.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
SMOKE="$ROOT/scripts/smoke-test.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CALLS="$WORK/calls.log"
export CALLS

# kubectl and docker that succeed and record the call. Nothing else is on the PATH, so whether this
# machine has a docker CLI is decided by the test.
mkdir "$WORK/kubectl" "$WORK/docker" "$WORK/none"
printf '#!/bin/sh\necho "kubectl $*" >> "$CALLS"\n' > "$WORK/kubectl/kubectl"
printf '#!/bin/sh\necho "docker $*" >> "$CALLS"\n' > "$WORK/docker/docker"
chmod +x "$WORK/kubectl/kubectl" "$WORK/docker/docker"

# The platform plumbing, copied out of smoke-test.sh so the test runs what the smoke test runs.
for fn in k8s_workload kube ctr_exec; do
    awk -v fn="$fn" '$0 ~ "^" fn "\\(\\) *\\{" { on = 1 } on { print } on && /^}/ { exit }' "$SMOKE"
done > "$WORK/platform.sh"

# Every if/elif in smoke-test.sh that probes a container (`ctr_exec <name> true`, or the ctr_available
# helper), its backslash-continued lines joined: "<line>\t<condition>".
awk '
    buf == "" { start = NR }
    /\\$/ { sub(/\\$/, ""); buf = buf $0 " "; next }
    { cmd = buf $0; buf = "" }
    cmd ~ /^[ \t]*(if|elif)[ \t]/ && (cmd ~ /ctr_exec [^ ]+ true/ || cmd ~ /ctr_available/) {
        sub(/^[ \t]*(if|elif)[ \t]+/, "", cmd); sub(/;[ \t]*then[ \t]*$/, "", cmd)
        gsub(/[ \t]+/, " ", cmd); print start "\t" cmd
    }' "$SMOKE" > "$WORK/gates.tsv"

FAILURES=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# gate <platform> <path dirs> <condition> -> the call log, joined by " | ", a tab, then "runs" or "skips"
gate() {
    : > "$CALLS"
    (
        PATH="$2"
        SMOKE_PLATFORM="$1" K8S_CONTEXT=kind-ecomdemo K8S_NAMESPACE=ecomdemo
        KAFKA_CONTAINER=ecomdemo-kafka PAYMENT_CONTAINER=ecomdemo-payment-service candidate=ecomdemo-candidate
        POSTGRES_CONTAINER=ecomdemo-db REDIS_CONTAINER=ecomdemo-cache REDIS_TLS="" PGUSER_=ecomdemo PGDB=ecomdemo
        # shellcheck source=/dev/null
        . "$WORK/platform.sh"
        # shellcheck source=scripts/smoke-clients.sh
        . "$ROOT/scripts/smoke-clients.sh"
        eval "$3"
    ) >/dev/null 2>&1
    local rc=$?
    awk 'NR > 1 { printf " | " } { printf "%s", $0 }' "$CALLS"
    if [ "$rc" -eq 0 ]; then printf '\truns'; else printf '\tskips'; fi
}

echo "KI-067 smoke-test container gates"

# 1. No gate asks for a docker CLI before it probes a container: ctr_exec already knows the platform.
# (`command -v docker` alone stays legitimate in front of compose-only docker commands: the stack
# guard, `docker inspect` of Alloy's health, `docker ps` of the published ports.)
MIXED="$(awk -F'\t' '$2 ~ /command -v docker/ { print "line " $1 ": " $2 }' "$WORK/gates.tsv")"
if [ -z "$MIXED" ]; then pass "no container probe is gated on 'command -v docker'"
else fail "no container probe is gated on 'command -v docker'"; printf '%s\n' "$MIXED" | cut -c1-160 | sed 's/^/        saw: /'; fi

# 2. Each gate, in file order, with what it must start on each platform. A new or removed gate fails
# here too, on purpose: add its row.
K="kubectl --context kind-ecomdemo -n ecomdemo exec"
K8S=(
    "$K deployment/candidate -- true"                                      # POSTGRES_CONTAINER discovery
    "$K deployment/candidate -- true"                                      # REDIS_CONTAINER discovery
    "$K deployment/app -- true"                                            # catalogue import error file
    "$K statefulset/kafka -- true"                                         # Kafka topics and log directory
    "$K statefulset/kafka -- true | $K statefulset/db -- true | $K statefulset/db -- psql -qtAX -U ecomdemo -d ecomdemo -c SELECT 1;" # Reliable event publishing
    "$K statefulset/kafka -- true"                                         # saga topics
    "$K statefulset/payment-db -- true"                                    # payment-db
    "$K statefulset/payment-db -- true"                                    # payment-db
    "$K statefulset/kafka -- true | $K statefulset/payment-db -- true | $K deployment/payment-service -- true" # dead letters
)
COMPOSE=(
    "docker exec ecomdemo-candidate true"
    "docker exec ecomdemo-candidate true"
    "docker exec ecomdemo-app true"
    "docker exec ecomdemo-kafka true"
    "docker exec ecomdemo-kafka true | docker exec ecomdemo-db true | docker exec ecomdemo-db psql -qtAX -U ecomdemo -d ecomdemo -c SELECT 1;"
    "docker exec ecomdemo-kafka true"
    "docker exec ecomdemo-payment-db true"
    "docker exec ecomdemo-payment-db true"
    "docker exec ecomdemo-kafka true | docker exec ecomdemo-payment-db true | docker exec ecomdemo-payment-service true"
)
COUNT="$(wc -l < "$WORK/gates.tsv" | tr -d ' ')"
if [ "$COUNT" -eq "${#K8S[@]}" ]; then pass "smoke-test.sh has the ${#K8S[@]} container gates this test knows"
else fail "smoke-test.sh has the ${#K8S[@]} container gates this test knows (found $COUNT)"; fi

expect() { # expect <name> <wanted calls> <runs|skips> <got: calls\tverdict>
    local want="$2"$'\t'"$3"
    if [ "$4" = "$want" ]; then pass "$1"
    else fail "$1"; printf '        got:  %s\n        want: %s\n' "${4//$'\t'/  -> }" "${want//$'\t'/  -> }"; fi
}

i=0
while IFS=$'\t' read -r line cond; do
    # k8s with kubectl and NO docker CLI: the pod is asked, through kubectl, and the section runs.
    expect "line $line, k8s, no docker CLI: the pod is probed" "${K8S[$i]:-?}" runs \
        "$(gate k8s "$WORK/kubectl" "$cond")"
    # compose, unchanged: docker exec, and the section runs.
    expect "line $line, compose: the container is probed" "${COMPOSE[$i]:-?}" runs \
        "$(gate compose "$WORK/docker" "$cond")"
    # compose without docker, unchanged: nothing answers, so the section SKIPs.
    expect "line $line, compose, no docker CLI: the section skips" "" skips \
        "$(gate compose "$WORK/none" "$cond")"
    i=$((i + 1))
done < "$WORK/gates.tsv"

if [ "$FAILURES" -gt 0 ]; then echo "test-smoke-gates: $FAILURES failed"; exit 1; fi
echo "test-smoke-gates: all passed"
