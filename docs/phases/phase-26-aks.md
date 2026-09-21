# Phase 26: Cloud Deployment (Optional)

| | |
|---|---|
| **Stage** | Stage 7: Distributed System |
| **Technology** | Azure AKS + ACR |
| **Branch** | `feature/phase-26-aks` |
| **PR title** | `Phase 26: Cloud Deployment (Optional)` |
| **Requires** | `phase-25-complete` tag exists on `main` |
| **Completion tag** | `phase-26-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Azure Kubernetes Service (AKS) + Azure Container Registry

**Goal:** Deploy to a real cloud cluster.

**What you'll implement**
- A GitHub Actions pipeline to ACR and AKS.
- Managed PostgreSQL and Redis where sensible.
- Secrets from Key Vault through workload identity.
- A documented teardown to avoid costs.

**Concepts to understand**
- Managed vs self-hosted infrastructure
- Workload identity
- CD pipelines and environment promotion

**Done when**
- The system runs on AKS through the pipeline, and the teardown steps are documented.

## Smoke test additions (`scripts/smoke-test.sh`)

The script runs against the AKS endpoint (only after your explicit go-ahead).

## Your manual steps (user)

Use your own Azure subscription and run `az login` yourself. Approve any cost-incurring step. Confirm the teardown at the end.
