# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 0: Repository Bootstrap
- **Branch:** main (bootstrap — the only phase that commits directly to main)
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none (Phase 0 has no PR; it bootstraps `main` itself)
- **Waiting for user:** no

## Checklist (copied from the phase's "What you'll implement")
- [x] Initialize the Git repository (`git init -b main`)
- [x] Add README.md, .gitignore, PR template, and scripts/.gitkeep
- [x] Initial commit on main (`chore: bootstrap repository and roadmap`)
- [ ] Create the GitHub repository and push (`gh repo create ecomdemo --source . --push --public`)
- [ ] User applies GitHub settings (manual)
- [ ] Verify settings through gh api
- [ ] Tag phase-00-complete

## Last test run
- n/a (no build yet; Maven project arrives in Phase 1)

## Open issues / blockers
- none

## Decisions this phase (copy to docs/decisions.md before the PR)
- Repository is **public**, named `ecomdemo` (user's choice, 2026-09-21).
- A pre-existing `mr-sujay-patil/EcomDemo` (an earlier attempt at this roadmap) was renamed to
  `EcomDemo-archive` to free the name. GitHub keeps redirects from the old URL.
- Merge method for every phase is **merge commit only** — superseding the squash-merge convention
  used in the archived repo.

## Next action
Run `gh repo create ecomdemo --source . --push --public`, then STOP and ask the user to apply the
GitHub settings listed in `docs/phases/phase-00-bootstrap.md` ("Your manual steps").
