#!/usr/bin/env bash
# Phase 25: create (or update) the local Kubernetes cluster and run the whole system in it.
#
#   scripts/k8s-up.sh            # cluster, Traefik, metrics-server, images, secrets, manifests
#   SKIP_BUILD=1 scripts/k8s-up.sh   # reuse the images already built (docker compose build)
#
# Safe to run again: every step checks what exists first. Then:
#   http://localhost:18080       the Ingress (Traefik) -> gateway-service -> everything else
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
retry helm --kube-context "$KCTX" upgrade --install metrics-server metrics-server/metrics-server \
    --version "$METRICS_SERVER_CHART_VERSION" --namespace kube-system -f k8s/platform/metrics-server-values.yaml --wait

step "Images"
# Built by compose (same Dockerfile, same tags), then COPIED into the kind node: the node is a
# container with its own image store and cannot see this machine's. The infrastructure images are
# loaded too, so the node never pulls from Docker Hub (rate limits, and it works offline).
APP_IMAGES="ecomdemo:latest ecomdemo-catalog:latest ecomdemo-customer:latest ecomdemo-inventory:latest
ecomdemo-notification:latest ecomdemo-payment:latest ecomdemo-gateway:latest"
INFRA_IMAGES="postgres:18-alpine redis:8-alpine apache/kafka:4.2.1"
if [ -z "${SKIP_BUILD:-}" ]; then
    docker compose build app catalog-service customer-service inventory-service \
        notification-service payment-service gateway-service
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
# The JWT signing key comes from .env, the same one compose uses (never printed, never in Git).
JWT_SECRET="${JWT_SECRET:-$(sed -n 's/^JWT_SECRET=//p' .env 2>/dev/null | tail -1)}"
if [ -z "$JWT_SECRET" ]; then
    echo "JWT_SECRET is not set and not in .env (see .env.example)" >&2
    exit 1
fi
# One Secret per service. A database password is GENERATED the first time and then kept: the
# database initialises itself with it on first start and stores it on its volume, so replacing the
# Secret later would lock the service out of its own database.
make_secret() { # make_secret <service> [DB_PASSWORD_KEY]
    local name="$1-secrets" key="${2:-}"
    if k -n "$NS" get secret "$name" >/dev/null 2>&1; then
        echo "secret/$name exists (kept)"
        return
    fi
    local args=(--from-literal=JWT_SECRET="$JWT_SECRET")
    [ -n "$key" ] && args+=(--from-literal="$key=$(openssl rand -hex 16)")
    k -n "$NS" create secret generic "$name" "${args[@]}" >/dev/null
    echo "secret/$name created"
}
make_secret app POSTGRES_PASSWORD
make_secret catalog-service CATALOG_DB_PASSWORD
make_secret customer-service CUSTOMER_DB_PASSWORD
make_secret inventory-service INVENTORY_DB_PASSWORD
make_secret notification-service NOTIFICATION_DB_PASSWORD
make_secret payment-service PAYMENT_DB_PASSWORD
make_secret gateway-service

step "Manifests (kubectl apply -k k8s/)"
k apply -k k8s/

step "Waiting for every workload to be ready"
for sts in db catalog-db customer-db inventory-db notification-db payment-db kafka; do
    k -n "$NS" rollout status "statefulset/$sts" --timeout=300s
done
for deploy in cache customer-service inventory-service catalog-service payment-service \
    notification-service app gateway-service; do
    k -n "$NS" rollout status "deployment/$deploy" --timeout=600s
done

step "Ready"
k -n "$NS" get pods -o wide
printf '\nThe Ingress is http://localhost:18080 - try: curl -s http://localhost:18080/api/products | head -c 200\n'
