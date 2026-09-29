# Phase 34: Authentication Hardening

| | |
|---|---|
| **Stage** | Stage 9: Production Hardening |
| **Technology** | Asymmetric JWT (RS256/ES256) + JWKS + login throttling |
| **Branch** | `feature/phase-34-auth-hardening` |
| **PR title** | `Phase 34: Authentication Hardening` |
| **Requires** | `phase-33-complete` tag exists on `main` |
| **Completion tag** | `phase-34-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Asymmetric JWT (RS256 or ES256) + a JWKS endpoint + login throttling

**Goal:** Only one service can issue tokens, and passwords can't be guessed at speed.

**What you'll implement**
- customer-service signs with a private key; every other service verifies with the public key from a JWKS endpoint (key rotation by `kid`).
- Scoped service identities: each service's token names the service and what it may call, and callees check it (replacing the one shared SERVICE role).
- Login throttling per username and per client, with backoff after repeated failures (and a metric).
- Update `docs/security.md` (API2, API5) with the result.

**Concepts to understand**
- Symmetric vs asymmetric signing, and why "can verify" must not mean "can mint"
- JWKS and key rotation
- Credential stuffing and brute force, and throttling vs lockout
- Least privilege for service-to-service calls

**Done when**
- No service except customer-service holds a signing key, a key rotation works without downtime, and repeated wrong passwords are throttled - each proven by automated tests.

## Smoke test additions (`scripts/smoke-test.sh`)

- The JWKS endpoint serves the public key; a token signed with any other key is rejected.
- Repeated failed logins for one username are throttled (429 with `Retry-After`).
- A service token cannot call an endpoint outside its scope.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.
