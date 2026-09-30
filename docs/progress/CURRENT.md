# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-30
- **Phase:** 33 — Authentication Hardening
- **Branch:** feature/phase-33-auth-hardening (cut from `main` at `5878be9`)
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification before this phase — PASSED (tag `ki-041-fixed` on `5878be9`)
KI-041: PR #53 merge commit (2 parents), 0 missing, 0 diff, branch alive. CI main run 36676951498
green (4 jobs). On main: verify 673/0, cold smoke (`down -v`) 457/0/0.

## Checklist (from the phase file's "What you'll implement")
- [ ] customer-service signs with a private key (RS256, `kid`); JWKS endpoint; every other service
      verifies via JWKS; no HS256 secret anywhere; key rotation without downtime
- [ ] Scoped service identities replace the shared SERVICE role; callees check scopes
- [ ] Login throttling per username and per client, backoff, 429 + Retry-After, metrics
- [ ] `docs/security.md` API2, API5; KI-014 and KI-015 closed
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
DONE (committed): common (JWKS decoder, ROLE_+SCOPE_ authorities, ClientCredentialsTokenProvider,
ServiceTokens scopes, TestJwt + TestJwtAutoConfiguration in the test-jar; common tests green);
customer-service main code compiles: SigningKeys/JwtConfig (RS256, kid), TokenService, OAuth2Controller
(/oauth2/jwks, /oauth2/token), ServiceClientProperties, login throttle (V2 migration, entity, service,
429 + Retry-After in AuthController). NOT yet run: customer tests.
NEXT, in order: (1) rewrite customer tests JwtConfigTest, TokenServiceTest, AuthApiIT (drop HS256;
add OAuth2Controller + throttle tests); (2) remove `ecomdemo.jwt.secret` from every other service's
properties, add `ecomdemo.jwt.jwk-set-uri` + `ecomdemo.service-identity.*` (app client-id
`ecomdemo-app`); gateway: reactive JWKS decoder + authorities(), ServiceIdentityFilter fetch off the
event loop, drop JwtKeyConfig import; (3) scope rules in catalog/inventory/payment SecurityConfig
(ServiceTokens.ROLE is gone); (4) fix remaining tests (list: grep jwtSigningKey|SecretKey|ServiceTokens.ROLE);
(5) compose/k8s env (JWT_JWK_SET_URI, SERVICE_TOKEN_URI, *_CLIENT_SECRET, JWT_SIGNING_KEY; k8s-up
generates), .env.example; USER STEP before the compose smoke: add the secrets to .env; (6) smoke,
docs, testing protocol, PR.

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
