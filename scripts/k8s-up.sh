#!/usr/bin/env bash
# Phase 25: create (or update) the local Kubernetes cluster and run the whole system in it.
#
#   scripts/k8s-up.sh            # cluster, Traefik, metrics-server, images, secrets, manifests
#   SKIP_BUILD=1 scripts/k8s-up.sh   # reuse the images already built (docker compose build)
#
# Safe to run again: every step checks what exists first. Then:
#   https://localhost:18443      the Ingress (Traefik, TLS from cert-manager) -> gateway-service -> everything else
#   http://localhost:18080       only redirects to the HTTPS port (KI-051); trust .local/ecomdemo-ca.crt, written below
#   scripts/k8s-smoke.sh         the smoke test against it
#   scripts/k8s-demo.sh          rolling update, rollback, self-healing and autoscaling demos
#   scripts/k8s-down.sh          delete the cluster
#
# Needs: docker, kind, kubectl, helm (the phase's manual steps).
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=ecomdemo
NS=ecomdemo
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
KCTX="kind-$CLUSTER"
# Third-party chart versions, pinned: a new chart release can change its values schema (it did,
# while this script was being written) and an unpinned install then fails - or worse, changes.
TRAEFIK_CHART_VERSION=41.6.0          # Traefik v3.7.13
METRICS_SERVER_CHART_VERSION=3.14.0   # metrics-server v0.9.0
CERT_MANAGER_CHART_VERSION=v1.20.4    # cert-manager v1.20.4 (KI-051)
k() { kubectl --context "$KCTX" "$@"; }

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# Chart downloads come from GitHub's release-asset host, which timed out three times in a row
# while this was written and then answered in 0.2 s. Three attempts, not one.
retry() {
    local attempt
    for attempt in 1 2 3; do
        "$@" && return 0
        echo "attempt $attempt failed; retrying in 10 s" >&2
        sleep 10
    done
    return 1
}

for tool in docker kind kubectl helm openssl; do
    command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done

step "Cluster"
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    echo "kind cluster '$CLUSTER' already exists"
else
    kind create cluster --config k8s/kind-cluster.yaml --wait 120s
fi

step "Platform: Traefik (Ingress controller) and metrics-server, with Helm"
helm repo add traefik https://traefik.github.io/charts >/dev/null 2>&1 || true
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ >/dev/null 2>&1 || true
helm repo update traefik metrics-server >/dev/null
retry helm --kube-context "$KCTX" upgrade --install traefik traefik/traefik \
    --version "$TRAEFIK_CHART_VERSION" --namespace traefik --create-namespace -f k8s/platform/traefik-values.yaml --wait
# KI-051: cert-manager issues and renews the Ingress certificate from a local CA (k8s/platform/ca.yaml).
helm repo add jetstack https://charts.jetstack.io >/dev/null 2>&1 || true
helm repo update jetstack >/dev/null
retry helm --kube-context "$KCTX" upgrade --install cert-manager jetstack/cert-manager \
    --version "$CERT_MANAGER_CHART_VERSION" --namespace cert-manager --create-namespace \
    -f k8s/platform/cert-manager-values.yaml --wait
# The CA's own objects need cert-manager's webhook to be answering, which `--wait` has just ensured
# but a freshly started webhook can still refuse the first request, so retry.
retry k apply -f k8s/platform/ca.yaml
k -n cert-manager wait --for=condition=Ready certificate/ecomdemo-local-ca --timeout=120s
k wait --for=condition=Ready clusterissuer/ecomdemo-ca --timeout=120s
retry helm --kube-context "$KCTX" upgrade --install metrics-server metrics-server/metrics-server \
    --version "$METRICS_SERVER_CHART_VERSION" --namespace kube-system -f k8s/platform/metrics-server-values.yaml --wait

step "Images"
# Built by compose (same Dockerfile, same tags), then COPIED into the kind node: the node is a
# container with its own image store and cannot see this machine's. The infrastructure images are
# loaded too, so the node never pulls from Docker Hub (rate limits, and it works offline).
APP_IMAGES="ecomdemo:latest ecomdemo-catalog:latest ecomdemo-customer:latest ecomdemo-inventory:latest
ecomdemo-notification:latest ecomdemo-payment:latest ecomdemo-assistant:latest ecomdemo-gateway:latest"
INFRA_IMAGES="postgres:18-alpine pgvector/pgvector:0.8.6-pg18-trixie redis:8-alpine apache/kafka:4.2.1"
if [ -z "${SKIP_BUILD:-}" ]; then
    docker compose build app catalog-service customer-service inventory-service \
        notification-service payment-service assistant-service gateway-service
fi
for image in $INFRA_IMAGES; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull -q "$image"
done
# One image at a time, exported for THIS machine's platform only. `kind load docker-image` exports
# the whole multi-platform index, and with Docker's containerd image store a pulled image (postgres,
# redis, kafka) lists platforms whose layers were never downloaded - the import then fails with
# "content digest ... not found". Saving one platform sidesteps it.
PLATFORM="linux/$(docker version --format '{{.Server.Arch}}')"
IMAGE_TAR="$(mktemp)"
for image in $APP_IMAGES $INFRA_IMAGES; do
    docker save --platform "$PLATFORM" -o "$IMAGE_TAR" "$image"
    kind load image-archive --name "$CLUSTER" "$IMAGE_TAR" >/dev/null
    echo "loaded $image"
done
rm -f "$IMAGE_TAR"

step "Namespace and Secrets"
k apply -f k8s/namespace.yaml
# Phase 33: no shared JWT secret any more. customer-service holds the only signing key (RSA) and
# the three service-client secrets; each calling service holds only its own client secret.
#
# Every value is taken from .env when it is set there (the same one compose uses), else KEPT from the
# Secret already in the cluster, else generated once. Nothing is ever printed.
env_value() { sed -n "s/^$1=//p" .env 2>/dev/null | tail -1; }
secret_value() { # secret_value <secret> <key> -> the stored value, or nothing
    k -n "$NS" get secret "$1" -o "jsonpath={.data.$2}" 2>/dev/null | base64 -d 2>/dev/null || true
}
# ensure_key <secret> <key> <value>: sets the key only when the secret or the key is missing, so a
# value already in the cluster (a database password above all: the database stored it on first start)
# is never replaced. That also upgrades a cluster created before Phase 33 in place.
ensure_key() {
    if ! k -n "$NS" get secret "$1" >/dev/null 2>&1; then
        k -n "$NS" create secret generic "$1" --from-literal="$2=$3" >/dev/null
        echo "secret/$1 created ($2)"
    elif [ -z "$(secret_value "$1" "$2")" ]; then
        k -n "$NS" patch secret "$1" --type merge -p "{\"stringData\":{\"$2\":\"$3\"}}" >/dev/null
        echo "secret/$1 gained $2"
    fi
}
pick() { # pick <.env name> <secret> <key> <generator>: .env, else the cluster's, else a new one
    local value; value="$(env_value "$1")"
    [ -z "$value" ] && value="$(secret_value "$2" "$3")"
    [ -z "$value" ] && value="$(eval "$4")"
    printf '%s' "$value"
}
NEW_SECRET='openssl rand -hex 32'
NEW_RSA_KEY='openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 -w0'
SIGNING_KEY="$(pick JWT_SIGNING_KEY customer-service-secrets JWT_SIGNING_KEY "$NEW_RSA_KEY")"
GATEWAY_SECRET="$(pick GATEWAY_CLIENT_SECRET customer-service-secrets GATEWAY_CLIENT_SECRET "$NEW_SECRET")"
APP_SECRET="$(pick APP_CLIENT_SECRET customer-service-secrets APP_CLIENT_SECRET "$NEW_SECRET")"
CATALOG_SECRET="$(pick CATALOG_CLIENT_SECRET customer-service-secrets CATALOG_CLIENT_SECRET "$NEW_SECRET")"

ensure_key customer-service-secrets JWT_SIGNING_KEY "$SIGNING_KEY"
ensure_key customer-service-secrets GATEWAY_CLIENT_SECRET "$GATEWAY_SECRET"
ensure_key customer-service-secrets APP_CLIENT_SECRET "$APP_SECRET"
ensure_key customer-service-secrets CATALOG_CLIENT_SECRET "$CATALOG_SECRET"
ensure_key gateway-service-secrets SERVICE_CLIENT_SECRET "$GATEWAY_SECRET"
ensure_key app-secrets SERVICE_CLIENT_SECRET "$APP_SECRET"
ensure_key catalog-service-secrets SERVICE_CLIENT_SECRET "$CATALOG_SECRET"
# A database password is GENERATED the first time and then kept: the database initialises itself
# with it on first start and stores it on its volume, so replacing it later would lock the service
# out of its own database.
ensure_key app-secrets POSTGRES_PASSWORD "$(openssl rand -hex 16)"
ensure_key catalog-service-secrets CATALOG_DB_PASSWORD "$(openssl rand -hex 16)"
ensure_key customer-service-secrets CUSTOMER_DB_PASSWORD "$(openssl rand -hex 16)"
ensure_key inventory-service-secrets INVENTORY_DB_PASSWORD "$(openssl rand -hex 16)"
ensure_key notification-service-secrets NOTIFICATION_DB_PASSWORD "$(openssl rand -hex 16)"
ensure_key payment-service-secrets PAYMENT_DB_PASSWORD "$(openssl rand -hex 16)"
# KI-050: one Redis password, shared by the cache and the four services that use it (app, catalog,
# gateway, assistant). Spring Boot reads SPRING_DATA_REDIS_PASSWORD from the environment, so the
# services need no code or config change; it reaches them through their own Secret like every other
# value. REDIS_PASSWORD in .env wins, else the cluster's, else a new one is generated and kept. A
# cluster created before this upgrades in place, but its running service pods only pick the new
# Secret key up when restarted: `kubectl -n ecomdemo rollout restart deploy`.
REDIS_PASSWORD="$(pick REDIS_PASSWORD cache-secrets REDIS_PASSWORD 'openssl rand -hex 16')"
ensure_key cache-secrets REDIS_PASSWORD "$REDIS_PASSWORD"
for svc_secret in app-secrets catalog-service-secrets gateway-service-secrets assistant-service-secrets; do
    ensure_key "$svc_secret" SPRING_DATA_REDIS_PASSWORD "$REDIS_PASSWORD"
done
# Services with nothing secret of their own still get their (empty-able) Secret: the manifests
# reference it. A placeholder key keeps `kubectl create secret` happy.
ensure_key assistant-service-secrets PHASE33_NO_SECRETS "none"

step "Manifests (kubectl apply -k k8s/)"
k apply -k k8s/

step "Waiting for every workload to be ready"
for sts in db catalog-db customer-db inventory-db notification-db payment-db kafka; do
    k -n "$NS" rollout status "statefulset/$sts" --timeout=300s
done
for deploy in cache customer-service inventory-service catalog-service payment-service \
    notification-service app assistant-service gateway-service; do
    k -n "$NS" rollout status "deployment/$deploy" --timeout=600s
done

step "Ready"
k -n "$NS" get pods -o wide
# KI-051: wait for the Ingress certificate, then write the CA that signed it where you can trust it. The
# file is the CA's PUBLIC certificate; its private key stays in the cluster.
step "TLS certificate and the CA to trust"
k -n "$NS" wait --for=condition=Ready certificate/ecomdemo-tls --timeout=120s
mkdir -p .local
k -n cert-manager get secret ecomdemo-local-ca -o 'jsonpath={.data.ca\.crt}' | base64 -d > .local/ecomdemo-ca.crt
echo "CA certificate written to .local/ecomdemo-ca.crt"

printf '\nThe Ingress is https://localhost:18443 (http://localhost:18080 redirects to it).\n'
printf 'Try: curl --cacert .local/ecomdemo-ca.crt -s https://localhost:18443/api/products | head -c 200\n'
printf 'Trust the CA once (README, "TLS") and the --cacert is not needed.\n'
