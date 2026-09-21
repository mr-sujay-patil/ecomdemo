# Phase 10: Containerization

| | |
|---|---|
| **Stage** | Stage 4: Quality & Delivery |
| **Technology** | Docker + Docker Compose |
| **Branch** | `feature/phase-10-docker` |
| **PR title** | `Phase 10: Containerization` |
| **Requires** | `phase-09-complete` tag exists on `main` |
| **Completion tag** | `phase-10-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Docker + Docker Compose

**Goal:** Package the app to run identically everywhere.

**What you'll implement**
- A multi-stage `Dockerfile` (JRE 21 runtime, non-root user, layered JAR).
- `compose.yaml` with the app and PostgreSQL, including health checks, volumes, and environment variables. Add `.env.example`.
- JVM container settings (`-XX:MaxRAMPercentage`).
- A comparison with Buildpacks (`spring-boot:build-image`) in `docs/decisions.md`.

**Concepts to understand**
- Images vs containers; layers and caching
- Multi-stage builds
- Container networking
- Volumes
- Running as non-root

**Done when**
- `docker compose up` runs the whole system, and the curl flow works.

## Smoke test additions (`scripts/smoke-test.sh`)

The whole script runs against the `docker compose up` stack instead of `spring-boot:run`.

## Your manual steps (user)

Keep Docker Desktop running with enough memory allocated (8 GB or more recommended).
