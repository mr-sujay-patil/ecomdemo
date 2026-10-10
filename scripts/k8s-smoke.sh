#!/usr/bin/env bash
# Phase 25: run scripts/smoke-test.sh against the kind cluster instead of compose.
#
#   scripts/k8s-up.sh && scripts/k8s-smoke.sh
#
# What changes for the smoke test, and nothing else does:
#   - BASE_URL is the INGRESS (Traefik, https://localhost:18443; plain 18080 only redirects), so every API check crosses it;
#   - SMOKE_PLATFORM=k8s makes its container helpers use `kubectl exec` / `kubectl scale`;
#   - the two services it reads directly (the app's actuator, payment-service's health) are reached
#     through `kubectl port-forward` on 18084 / 18086 - ports compose does not use, so both stacks
#     can be up at once;
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

# KI-051: the API is HTTPS now. The CA that signed the Ingress certificate is exported by k8s-up.sh to
# .local/ecomdemo-ca.crt (public); take it from the cluster if the file is missing. curl reads
# CURL_CA_BUNDLE and Python SSL_CERT_FILE, so the smoke test verifies the certificate like any client.
TLS_CA="${TLS_CA:-.local/ecomdemo-ca.crt}"
if [ ! -s "$TLS_CA" ]; then
    mkdir -p "$(dirname "$TLS_CA")"
    kubectl --context "$CONTEXT" -n cert-manager get secret ecomdemo-local-ca -o 'jsonpath={.data.ca\.crt}' \
        | base64 -d > "$TLS_CA"
fi
export CURL_CA_BUNDLE="$TLS_CA" SSL_CERT_FILE="$TLS_CA"
# KI-060: the certificate also names the frontend team's shop host. Only the TLS handshake is checked
# (verified against the CA, as `shop.localhost`): any HTTP status will do, because the shop itself may
# not be deployed, and then the request falls through to our Ingress.
if ! curl -sS -o /dev/null --resolve shop.localhost:18443:127.0.0.1 https://shop.localhost:18443/; then
    echo "FAIL: https://shop.localhost:18443 does not present a certificate for shop.localhost (KI-060)" >&2
    exit 1
fi
echo "PASS: the edge certificate is valid for shop.localhost (KI-060)"
# KI-056: the services serve HTTPS (their certificates name `localhost`, for exactly these port-forwards).
kubectl --context "$CONTEXT" -n "$NS" port-forward svc/app 18084:8080 >/dev/null 2>&1 &
kubectl --context "$CONTEXT" -n "$NS" port-forward svc/payment-service 18086:8086 >/dev/null 2>&1 &
for _ in $(seq 1 30); do
    curl -fsS https://localhost:18084/actuator/health >/dev/null 2>&1 \
        && curl -fsS https://localhost:18086/actuator/health >/dev/null 2>&1 && break
    sleep 1
done

cp scripts/smoke-test.sh "$WORK/smoke-test.sh"
# What the copy sources from its own directory (KI-055, KI-064).
cp scripts/smoke-burst.sh "$WORK/smoke-burst.sh"
cp scripts/smoke-clients.sh "$WORK/smoke-clients.sh"
NOT_IN_CLUSTER="http://observability-stays-in-compose.invalid"
SMOKE_PLATFORM=k8s \
BASE_URL="${BASE_URL:-https://localhost:18443}" \
APP_URL=https://localhost:18084 \
PAYMENT_URL=https://localhost:18086 \
PROMETHEUS_URL="$NOT_IN_CLUSTER" GRAFANA_URL="$NOT_IN_CLUSTER" \
LOKI_URL="$NOT_IN_CLUSTER" TEMPO_URL="$NOT_IN_CLUSTER" ALLOY_URL="$NOT_IN_CLUSTER" \
SMOKE_STATE_FILE="${SMOKE_STATE_FILE:-.smoke-state-k8s}" \
    bash "$WORK/smoke-test.sh"
