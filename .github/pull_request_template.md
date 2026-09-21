## Phase

<!-- e.g. Phase 04: PostgreSQL — link the phase file: docs/phases/phase-04-postgresql.md -->

Phase XX: <Technology> — `docs/phases/phase-XX-<slug>.md`

## What changed

<!-- Bullet points, one per "What you'll implement" item. Note new dependencies with versions,
     new endpoints, new configuration and any new infrastructure (containers, ports, env vars). -->

-

## How it was tested

<!-- Test counts per type (unit / integration / slice), the smoke test result,
     and a link to the report. -->

- `./mvnw clean verify`:
- `scripts/smoke-test.sh`:
- Test report: `docs/test-reports/phase-XX.md`

## Concepts learned

<!-- 2-4 sentences per concept, tied to the code actually written in this PR. -->

-

## Checklist

- [ ] All tests pass (`./mvnw clean verify`) — no test was disabled, skipped or deleted
- [ ] Smoke test passes (`scripts/smoke-test.sh`)
- [ ] Docs updated (README, `docs/decisions.md`, `docs/progress/*`, ROADMAP tracker → 🔵)
- [ ] Test report written to `docs/test-reports/phase-XX.md`
- [ ] No secrets in code, commits or this description
- [ ] Only this phase's scope is included
- [ ] Merge with **"Create a merge commit"** — not squash, not rebase
- [ ] Do **not** delete the branch after merging
