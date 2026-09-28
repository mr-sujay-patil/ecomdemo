#!/usr/bin/env bash
# Phase 25: run scripts/smoke-test.sh against the kind cluster instead of compose.
#
#   scripts/k8s-up.sh && scripts/k8s-smoke.sh
#
# What changes for the smoke test, and nothing else does:
#   - BASE_URL is the INGRESS (Traefik, localhost:18080), so every API check crosses it;
#   - SMOKE_PLATFORM=k8s makes its container helpers use `kubectl exec` / `kubectl scale`;
#   - the three services it reads directly (the app's actuator, payment-service's health,
#     catalog-service's metrics) are reached through `kubectl port-forward` on 18084 / 18086 /
#     18081 - ports compose does not use, so both stacks can be up at once;
#   - Prometheus, Grafana, Loki and Tempo stay in compose (the user's Phase 25 decision), so their
#     URLs point nowhere and those checks are SKIPPED - reported as skipped, never as passed.
#
# It runs a COPY of the smoke test: bash reads a script while executing it, so editing
# scripts/smoke-test.sh during a run would change the run (Phase 24 learned this the hard way).
set -euo pipefail
cd "$(dirname "$0")/.."

CONTEXT=kind-ecomdemo
NS=ecomdemo
kubectl --context "$CONTEXT" -n "$NS" get deployment gateway-service >/dev/null \
    || { echo "no cluster - run scripts/k8s-up.sh first" >&2; exit 1; }

WORK="$(mktemp -d)"
cleanup() { kill $(jobs -p) 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

kubectl --context "$CONTEXT" -n "$NS" port-forward svc/app 18084:8080 >/dev/null 2>&1 &
kubectl --context "$CONTEXT" -n "$NS" port-forward svc/payment-service 18086:8086 >/dev/null 2>&1 &
kubectl --context "$CONTEXT" -n "$NS" port-forward svc/catalog-service 18081:8081 >/dev/null 2>&1 &
for _ in $(seq 1 30); do
    curl -fsS http://localhost:18084/actuator/health >/dev/null 2>&1 \
        && curl -fsS http://localhost:18086/actuator/health >/dev/null 2>&1 \
        && curl -fsS http://localhost:18081/actuator/health >/dev/null 2>&1 && break
    sleep 1
done

cp scripts/smoke-test.sh "$WORK/smoke-test.sh"
NOT_IN_CLUSTER="http://observability-stays-in-compose.invalid"
SMOKE_PLATFORM=k8s \
BASE_URL="${BASE_URL:-http://localhost:18080}" \
APP_URL=http://localhost:18084 \
PAYMENT_URL=http://localhost:18086 \
CATALOG_URL=http://localhost:18081 \
PROMETHEUS_URL="$NOT_IN_CLUSTER" GRAFANA_URL="$NOT_IN_CLUSTER" \
LOKI_URL="$NOT_IN_CLUSTER" TEMPO_URL="$NOT_IN_CLUSTER" ALLOY_URL="$NOT_IN_CLUSTER" \
SMOKE_STATE_FILE="${SMOKE_STATE_FILE:-.smoke-state-k8s}" \
    bash "$WORK/smoke-test.sh"
