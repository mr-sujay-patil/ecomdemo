# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-30
- **Phase:** 33 — Authentication Hardening
- **Branch:** feature/phase-33-auth-hardening (cut from `main` at `5878be9`)
- **Step:** WAITING_FOR_USER
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** YES (add the Phase 33 secrets to .env, then say `done`)

## Merge verification before this phase — PASSED (tag `ki-041-fixed` on `5878be9`)
KI-041: PR #53 merge commit (2 parents), 0 missing, 0 diff, branch alive. CI main run 36676951498
green (4 jobs). On main: verify 673/0, cold smoke (`down -v`) 457/0/0.

## Checklist (from the phase file's "What you'll implement")
- [x] customer-service signs with a private key (RS256, `kid`); JWKS endpoint; every other service
      verifies via JWKS; no HS256 secret anywhere; key rotation without downtime
- [x] Scoped service identities replace the shared SERVICE role; callees check scopes
- [x] Login throttling per username and per client, backoff, 429 + Retry-After, metrics
- [x] `docs/security.md` API2, API5; KI-014 and KI-015 closed
- [ ] Done when: only customer-service holds a signing key, rotation without downtime, repeated wrong
      passwords throttled - each proven by automated tests
- [ ] Smoke: JWKS serves the public key, foreign-key token rejected; 429 + Retry-After; out-of-scope
      service token refused
- [ ] Testing protocol, report, README, decisions, RECENT, tracker 🔵, PR

## Design (user decisions 2026-09-30: minimal endpoint, PostgreSQL throttle table)
- Signing: customer-service only, RS256. Keys from `ecomdemo.jwt.signing-keys` (kid + PKCS#8 PEM,
  first = active, the rest published for verification); unset → an ephemeral key with a warning.
  `GET /oauth2/jwks` (public keys, all published kids). Rotation: publish new → make active → drop old
  after the token lifetime.
- Verifying: every service `NimbusJwtDecoder.withJwkSetUri(ecomdemo.jwt.jwk-set-uri)` (gateway:
  reactive), issuer checked, JWKS cached and refetched on an unknown kid. Tests: a fixed test key pair.
- Service identities: minimal OAuth2 client credentials, `POST /oauth2/token` (HTTP Basic client id +
  secret, BCrypt-hashed in config), token `sub` = client id, `scope` claim, no roles. Clients:
  gateway-service `catalog:read`; ecomdemo-app `catalog:read catalog:write inventory:read
  inventory:write payment:settle`; catalog-service `inventory:read`. Secrets from env
  (`*_CLIENT_SECRET`); k8s-up.sh generates them; compose needs them in `.env` (USER STEP).
- Authorities: `roles` → ROLE_*, `scope` → SCOPE_*. Callees: catalog reads CUSTOMER|ADMIN|
  SCOPE_catalog:read, writes ADMIN|SCOPE_catalog:write; inventory reads ADMIN|SCOPE_inventory:read,
  writes ADMIN|SCOPE_inventory:write; payment `/internal/**` SCOPE_payment:settle.
- Throttling (customer-db, Flyway): failures per username and per client IP (last X-Forwarded-For
  hop from the gateway), window + exponential backoff; 429 + Retry-After; success resets the username;
  counters `ecomdemo.auth.login.failures`, `ecomdemo.auth.login.throttled{key}`.

## Next action
All code, config, smoke additions and docs (README, security.md, decisions, KNOWN_ISSUES) are
committed; `./mvnw clean verify` 693/0 before the last 4 scope tests (those green separately).
WAITING: the user appends JWT_SIGNING_KEY and GATEWAY/APP/CATALOG_CLIENT_SECRET to .env with the
command in .env.example (check presence only: `grep -q "^NAME=." .env`, never print values), then says
`done`. THEN: full `./mvnw clean verify`; cold compose (`down -v`, `up --build --wait`) + smoke copy;
kind (`SKIP_BUILD=1 scripts/k8s-up.sh`, rollout restart all Deployments, `scripts/k8s-smoke.sh`); test
report docs/test-reports/phase-33.md, RECENT.md rotation, tracker 🔵, PR `Phase 33: Authentication Hardening`.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- Verification uses a COLD stack (`down -v`, `up --build --wait`, `.smoke-state` removed).
- kind: `SKIP_BUILD=1 scripts/k8s-up.sh` does NOT restart pods; `kubectl rollout restart` all Deployments.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy. Never print `.env`.
- The old per-session scratchpad is gone; logs go to `~/.cache/ecomdemo-claude/`.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

## ⚠️ Carried, not fixed
Tracked in `docs/KNOWN_ISSUES.md` (open rows: KI-002..011, KI-039, KI-040, and the triage sections).
