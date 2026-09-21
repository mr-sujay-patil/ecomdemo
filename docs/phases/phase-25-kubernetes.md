# Phase 25: Container Orchestration

| | |
|---|---|
| **Stage** | Stage 7: Distributed System |
| **Technology** | Kubernetes (kind/minikube) |
| **Branch** | `feature/phase-25-kubernetes` |
| **PR title** | `Phase 25: Container Orchestration` |
| **Requires** | `phase-24-complete` tag exists on `main` |
| **Completion tag** | `phase-25-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Kubernetes (kind or minikube)

**Goal:** Run the system the way production does.

**What you'll implement**
- Deployment, Service, ConfigMap, and Secret per service.
- Probes and resource limits.
- An Ingress in front of the gateway.
- Infrastructure through Helm charts or manifests.
- An HPA.
- Rolling update and self-healing demos.

**Concepts to understand**
- Pods, Deployments, ReplicaSets, and Services
- ConfigMaps vs Secrets
- Probes
- Rollouts and rollbacks
- Stateless scaling

**Done when**
- The full flow works through the Ingress on the local cluster.

## Smoke test additions (`scripts/smoke-test.sh`)

The script runs against the Ingress URL. Delete one pod: the flow still works while it restarts.

## Your manual steps (user)

Install kind (or minikube) and kubectl, and give Docker Desktop enough resources (12 GB or more of RAM recommended).
