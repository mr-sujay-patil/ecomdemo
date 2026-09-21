## Phase 00: Repository Bootstrap (tag: phase-00-complete, no PR by design)
**What exists now:** Public repo github.com/mr-sujay-patil/ecomdemo with the docs package,
README, .gitignore, PR template and `scripts/`. `main` protected: PR required, force pushes and
deletions blocked, `enforce_admins: true`, merge commits only (squash and rebase disabled).
**Key code:** none (no application code in this phase).
**Config & infrastructure:** GitHub settings applied via `gh api`; a direct push to `main` is
rejected with GH006 (verified, not assumed).
**Tests:** none applicable; no build exists yet.
**Gotchas:** the earlier attempt at this roadmap was renamed to `EcomDemo-archive` to free the
name. Phase 0 is the only phase permitted to commit to `main`.
**Follow-ups (not done, out of scope):** CI checks on the protected branch — Phase 11.
