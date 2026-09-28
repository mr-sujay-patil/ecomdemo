#!/usr/bin/env bash
# Phase 25: watch Kubernetes do the things the phase is about.
#
#   scripts/k8s-demo.sh rollout    rolling update of catalog-service while traffic flows
#   scripts/k8s-demo.sh rollback   `kubectl rollout undo` to the previous revision
#   scripts/k8s-demo.sh selfheal   delete a pod and watch its ReplicaSet replace it
#   scripts/k8s-demo.sh hpa        load catalog-service and watch the HPA add pods
#
# Needs the cluster from scripts/k8s-up.sh.
set -euo pipefail
CONTEXT=kind-ecomdemo
NS=ecomdemo
INGRESS="${INGRESS:-http://localhost:18080}"
k() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }

traffic() { # traffic <seconds> -> prints "N requests, M not 200"
    local until=$(( $(date +%s) + $1 )) total=0 bad=0 code
    while [ "$(date +%s)" -lt "$until" ]; do
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$INGRESS/api/products")"
        total=$((total + 1)); [ "$code" = "200" ] || bad=$((bad + 1))
        sleep 0.1
    done
    echo "$total requests, $bad not 200"
}

case "${1:-}" in
rollout)
    echo "Revision before: $(k get deploy catalog-service -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}')"
    echo "Restarting catalog-service - a new ReplicaSet, pods swapped one at a time (maxSurge 1, maxUnavailable 0)"
    traffic 90 > /tmp/ecomdemo-rollout-traffic &
    k rollout restart deployment/catalog-service
    k rollout status deployment/catalog-service
    wait
    echo "During the rollout: $(cat /tmp/ecomdemo-rollout-traffic)"
    k rollout history deployment/catalog-service | tail -4
    k get replicasets -l app.kubernetes.io/name=catalog-service
    ;;
rollback)
    k rollout history deployment/catalog-service | tail -4
    k rollout undo deployment/catalog-service
    k rollout status deployment/catalog-service
    echo "Now at revision $(k get deploy catalog-service -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}')"
    echo "(undo makes the OLD pod template current again - it is recorded as a NEW revision number)"
    echo "kubectl warns that the object was created with 'kubectl apply': undo changed the LIVE"
    echo "Deployment, not k8s/services/catalog-service.yaml, so the next 'kubectl apply -k k8s/' puts"
    echo "the file's version back. In a GitOps setup a rollback is a revert of the file, for that reason."
    ;;
selfheal)
    victim="$(k get pods -l app.kubernetes.io/name=gateway-service -o jsonpath='{.items[0].metadata.name}')"
    echo "Deleting $victim; the API keeps answering from the other gateway pod:"
    k delete pod "$victim" --wait=false
    traffic 20
    k get pods -l app.kubernetes.io/name=gateway-service
    echo "A new pod replaced it: the ReplicaSet wants 2 and sees 1, so it makes one. Nobody asked."
    ;;
hpa)
    start="$(k get deploy catalog-service -o jsonpath='{.spec.replicas}')"
    echo "catalog-service runs $start pod(s) now (min 2, max 4)."
    [ "$start" -gt 2 ] && echo "(Already above 2: recent load scaled it up, and scale-down waits 5 minutes.)"
    echo "Load: 8 loops inside the cluster calling catalog-service for 3 minutes"
    k delete pod catalog-load --ignore-not-found >/dev/null
    k run catalog-load --image=ecomdemo-catalog:latest --image-pull-policy=Never --restart=Never \
        --command -- sh -c 'for i in 1 2 3 4 5 6 7 8; do (end=$(( $(date +%s) + 180 )); while [ $(date +%s) -lt $end ]; do wget -q -O /dev/null http://catalog-service:8081/api/products; done) & done; wait' >/dev/null
    most="$start"
    for _ in $(seq 1 24); do
        line="$(k get hpa catalog-service --no-headers)"
        echo "$line"
        now="$(k get deploy catalog-service -o jsonpath='{.spec.replicas}')"
        [ "$now" -gt "$most" ] && most="$now"
        sleep 10
    done
    k delete pod catalog-load --wait=false >/dev/null
    echo "Started at $start pod(s); the HPA went up to $most."
    echo "Scale-DOWN waits 5 minutes (stabilizationWindowSeconds in k8s/hpa.yaml) so a lull does not flap."
    ;;
*)
    sed -n '2,10p' "$0"
    exit 1
    ;;
esac
