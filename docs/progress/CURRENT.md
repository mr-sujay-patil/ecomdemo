# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-06
- **Phase:** 34 — Product Images (requested by the web team's KI-002; approved 2026-10-06: yes, seed-only)
- **Branch:** feature/phase-34-product-images (cut from `main` at `8e929fa`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #59 https://github.com/mr-sujay-patil/ecomdemo/pull/59
- **Waiting for user:** YES (review of PR #59)

## Merge verification before this phase
Phase 33 and fixes KI-039, KI-042, KI-043 are verified in `main` (tags `phase-33-complete`, `ki-039-fixed`,
`ki-042-fixed`, `ki-043-fixed`). CI on `8e929fa` all green; `./mvnw clean verify` 701 tests, 0 failed.

## Checklist (Phase 34)
- [x] V5 migration: nullable `product.image_file`; set for some seeded products (others stay null)
- [x] Seed images: small original SVGs from a script in `scripts/`, shipped as classpath resources
- [x] `ProductResponse.imageUrl` (additive, nullable, gateway-relative `/api/products/{id}/image`); search follows
- [x] `GET /api/products/{id}/image`: allow-listed types, 404 unknown/imageless, ETag + 304, Cache-Control,
      nosniff, CORP cross-origin, CSP on SVG; no client-supplied paths; size cap test
- [x] Anonymous through the gateway proven by a test (no gateway change expected)
- [x] Smoke additions; OpenAPI; README, `docs/security.md`, `docs/decisions.md`
- [x] Testing protocol in full, `docs/test-reports/phase-34.md`, RECENT.md rotated, tracker 🔵, PR

## Next action
PR #59 is open. STOP: wait for the user's review. After the merge: merge verification (git checks, CI on main
incl. `Image scan` and publish, `./mvnw clean verify`, cold compose smoke if no foreign stack is up), tag
`phase-34-complete`, mark the tracker row ✅. Then tell the user to relay to the web team: tag
`phase-34-complete`, `imageUrl` is a path relative to the gateway origin. Still open: KI-040, KI-044,
KI-045 (mvnw.cmd eol; `stash@{0}` holds the noise), KI-002..011.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports. Never stop or `down` their project; ask the user. kind does not conflict.
- Verification uses a COLD stack (`down -v`, `up --build --wait`, `.smoke-state` removed).
- Never edit `scripts/smoke-test.sh` while it runs; run a copy. Never print `.env`.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.
