# Phase 3: API Documentation

| | |
|---|---|
| **Stage** | Stage 1: Foundation |
| **Technology** | springdoc-openapi |
| **Branch** | `feature/phase-03-openapi` |
| **PR title** | `Phase 03: API Documentation` |
| **Requires** | `phase-02-complete` tag exists on `main` |
| **Completion tag** | `phase-03-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** springdoc-openapi (OpenAPI 3 / Swagger UI)

**Goal:** Make the API self-documenting and explorable in a browser.

**What you'll implement**
- springdoc-openapi (the version compatible with your Spring Boot version). Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs`.
- `@Tag`, `@Operation`, and `@ApiResponse` on controllers, with `@Schema` examples on DTOs.
- Documented error responses.

**Concepts to understand**
- The OpenAPI specification; contract-first vs code-first

**Done when**
- Every endpoint is callable from Swagger UI, with descriptions and examples.

## Smoke test additions (`scripts/smoke-test.sh`)

`GET /v3/api-docs` returns 200 and contains every `/api` path. `GET /swagger-ui.html` returns 200 or a redirect to the UI.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.
