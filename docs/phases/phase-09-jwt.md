# Phase 9: JWT Authentication

| | |
|---|---|
| **Stage** | Stage 3: Security |
| **Technology** | JWT (OAuth2 Resource Server) |
| **Branch** | `feature/phase-09-jwt` |
| **PR title** | `Phase 09: JWT Authentication` |
| **Requires** | `phase-08-complete` tag exists on `main` |
| **Completion tag** | `phase-09-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** JWT (Spring Security OAuth2 Resource Server)

**Goal:** Replace HTTP Basic with token-based authentication.

**What you'll implement**
- `POST /api/auth/login` returns a signed JWT with role claims and a short expiry.
- Resource Server validation of the token on every request, with the key from an environment variable.
- Swagger UI configured for Bearer tokens.
- Optional: a refresh token endpoint.

**Concepts to understand**
- JWT structure
- Stateless vs session-based authentication
- HMAC vs RSA signing
- Expiry, refresh, and revocation
- What OAuth2 and OIDC add

**Done when**
- Secured endpoints work with a Bearer token, and expired or tampered tokens are rejected.

## Smoke test additions (`scripts/smoke-test.sh`)

Login returns a JWT. The full flow runs with a Bearer token. A tampered token → 401, and an expired token → 401 (use a short test expiry).

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.
