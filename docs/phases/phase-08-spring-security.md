# Phase 8: Spring Security

| | |
|---|---|
| **Stage** | Stage 3: Security |
| **Technology** | Spring Security |
| **Branch** | `feature/phase-08-spring-security` |
| **PR title** | `Phase 08: Spring Security` |
| **Requires** | `phase-07-complete` tag exists on `main` |
| **Completion tag** | `phase-08-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Spring Security

**Goal:** Introduce users, authentication, and authorization.

**What you'll implement**
- `customer` feature: registration (BCrypt), profile, and a `users` table (Flyway). The admin user is seeded by a migration.
- Roles `CUSTOMER` and `ADMIN`, with HTTP Basic authentication.
- Rules: product reads are public; product writes require ADMIN; cart and orders require CUSTOMER.
- Cart per user (replaces the shared cart), and orders owned by the user.
- `@PreAuthorize` so users only see their own orders.
- 401 and 403 responses in the standard error format.

**Concepts to understand**
- The security filter chain
- Authentication vs authorization
- `UserDetailsService` and `PasswordEncoder`
- Hashing vs encryption
- CSRF for stateless APIs

**Done when**
- `@WithMockUser` tests cover allowed and denied access for each role.

## Smoke test additions (`scripts/smoke-test.sh`)

Anonymous product read → 200. Anonymous cart access → 401. A customer creating a product → 403, and an admin → 201. Customer A cannot read customer B's order (403 or 404). The full flow runs as a logged-in customer.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.
