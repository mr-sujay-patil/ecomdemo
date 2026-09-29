# Phase 33: API Documentation Across Services

| | |
|---|---|
| **Stage** | Stage 9: Production Hardening |
| **Technology** | springdoc-openapi (per service) + gateway-aggregated Swagger UI |
| **Branch** | `feature/phase-33-api-docs` |
| **PR title** | `Phase 33: API Documentation Across Services` |
| **Requires** | `phase-32-complete` tag exists on `main` |
| **Completion tag** | `phase-33-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** springdoc-openapi in every service, and one Swagger UI at the gateway that aggregates them

**Goal:** Restore what Phase 03 gave the monolith and Phase 21 lost in the split: every endpoint behind the gateway is described by an OpenAPI document and can be explored and called from one Swagger UI.

**Why this phase exists:** springdoc and `OpenApiConfig` already reach every service through `common`, but each service's `SecurityConfig` blocks `/v3/api-docs` and `/swagger-ui/**`, the gateway routes only `/api/**`, and all six specs say "EcomDemo API" with server `localhost:8080`. The README's `http://localhost:8080/swagger-ui.html` returns 404, and `OpenApiDocumentationTest` records that the catalogue schema is documented nowhere.

**What you'll implement**
- Each service with an HTTP API (catalog, customer, inventory, assistant, and the app) publishes its own `/v3/api-docs`: docs paths opened in its `SecurityConfig`, its own title and description, and the springdoc properties the app already uses. Services with no public API (payment, notification) keep their docs closed, or have them disabled.
- `OpenApiConfig` in `common` takes the service's name and description from configuration instead of hard-coding "EcomDemo API", and names the gateway as the only server.
- Gateway routes `/v3/api-docs/{service}` → that service's `/v3/api-docs` (path rewrite), and serves one Swagger UI (springdoc's WebFlux UI) with a dropdown of the service specs. "Try it out" goes through the gateway, so the gateway's JWT checks and rate limits still apply.
- A spec test per documented service, in the style of `OpenApiDocumentationTest`: the document loads, every operation has a summary, and secured operations declare `bearerAuth`.
- Resolve the Phase 21 follow-up: flip `apiDocs_theCatalogueSchemaIsNoLongerThisApplicationsToDocument` into a catalog-service test that asserts `ProductWrite` and its constraints are documented, and delete the stale comment.
- Update the README (Swagger UI URL, per-service docs) and record the decisions.

**Concepts to understand**
- Code-first OpenAPI in a distributed system: one spec per service vs one merged spec
- Aggregating specs at a gateway, and why "Try it out" should go through the gateway rather than straight to a service
- Why a service's docs endpoint needs its own security rule, and what an open spec reveals
- Servlet (webmvc) vs reactive (webflux) springdoc starters, and why the gateway needs the reactive one

**Done when**
- `http://localhost:8080/swagger-ui.html` (the gateway) lists every documented service. Each service's spec loads from the dropdown, and an authorized "Try it out" call succeeds through the gateway. Proven by automated tests and the smoke script.

## Smoke test additions (`scripts/smoke-test.sh`)

- The gateway's Swagger UI answers 200, and `/v3/api-docs/{service}` returns an OpenAPI 3 document for each documented service.
- The catalog spec contains `ProductWrite`; each spec's title names its service.
- Payment and notification do not expose a docs endpoint through the gateway.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.
