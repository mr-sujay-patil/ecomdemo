# 🤖 Claude Code Execution Protocol

How Claude Code runs the roadmap, one phase at a time, from a single kickoff prompt.

## 1. Session start: resume sequence (always, in this order)

1. `CLAUDE.md` is loaded automatically.
2. Read this file (`docs/process/execution-protocol.md`).
3. **Determine the state from Git** (the source of truth):

       git fetch origin --prune
       git tag --list "phase-*"
       gh pr list --state all --limit 10
       git branch -a --list "*feature/phase-*"
       git status

4. Read `docs/progress/CURRENT.md` (the in-phase checkpoint).
5. Read `docs/progress/RECENT.md` (summaries of the last two phases).
6. Read **only** the current phase file in `docs/phases/`.
7. Read other files only when a step needs them (see `docs/process/context-management.md`).

If Git and `CURRENT.md` disagree, trust Git, report the mismatch, and ask the user before acting.

## 2. State → action

| Situation | Action |
|---|---|
| No open phase PR; `phase-(N-1)-complete` tagged; no branch for phase N | **Start phase N** (section 3) |
| Branch for phase N exists, no PR yet | **Resume** from `CURRENT.md` → "Next action", on that branch |
| PR for phase N is open | **Stay stopped.** Tell the user the PR awaits review or merge |
| PR for phase N merged, no `phase-N-complete` tag | Run **merge verification** (section 5) |
| Any other combination | Report the state and ask the user |

## 3. Running a phase (autonomous part)

| Step | Action | Checkpoint (`CURRENT.md` step) |
|---|---|---|
| 1 | Pre-flight (see `git-workflow.md`, Command Reference 1). On failure, stop and report | `PREFLIGHT` |
| 2 | Cut `feature/phase-XX-<slug>` from the latest `main` and push it | `BRANCHED` |
| 3 | **Housekeeping commit** `docs(progress): start phase XX`: mark the previous phase ✅ in `docs/ROADMAP.md`, record its merge verification result, and reset `CURRENT.md` for phase XX, with the checklist copied from the phase's "What you'll implement" | `BRANCHED` |
| 4 | If the user asked for `plan first`: post the plan and **stop** until it is approved | `PLANNING` |
| 5 | Implement checklist items one by one with small Conventional Commits. Tick each item in `CURRENT.md` and commit the checkpoint with the related work | `IMPLEMENTING` |
| 6 | Run `docs/process/testing-protocol.md` in full. Fix until green. Write `docs/test-reports/phase-XX.md` | `TESTING` |
| 7 | Update the README and `docs/decisions.md`. Write this phase's summary into `RECENT.md` and rotate it (see `context-management.md`). Set the tracker to 🔵 | `TESTING` |
| 8 | Push, then `gh pr create --base main` with the PR template filled in | `PR_OPEN` |
| 9 | **STOP.** Send the Phase Review Report (section 6) | `PR_OPEN` |

## 4. User commands

| User says | Claude Code does |
|---|---|
| `merged, continue` | Merge verification (section 5). If it passes, tag the phase, then **start the next phase** (section 3) |
| `merged, stop` | Merge verification and tag, then stop |
| `approved, merge it` | `gh pr merge <n> --merge` (**never** `--squash`, `--rebase`, or `--delete-branch`), then behave as for `merged, continue` |
| `approved` or `continue` while the PR is still open | **Do not proceed.** Say that the PR must be merged first, or that the user can say `approved, merge it` |
| `changes: <feedback>` | Set the step to `IMPLEMENTING` on the **same branch**, fix, re-run the full testing protocol, push, update the PR and report, and stop again |
| `plan first` | Applies to the next phase start (section 3, step 4) |
| `status` | Report the phase, step, branch, PR state, last tag, and next action. No changes |
| `continue` (new session) | Run the resume sequence (section 1) and carry on |
| `stop` | Finish the current atomic action, update and commit `CURRENT.md`, and stop |
| `done` | Confirms that a manual step you asked for is complete. Verify it, then continue |

## 5. Merge verification

1. Run Command Reference step 5 and the Verification Checklist in `git-workflow.md`.
2. Re-run `./mvnw clean verify` and `scripts/smoke-test.sh` **on `main`** (from Phase 11 onwards, also confirm CI on `main` is green).
3. If everything passes, tag and push `phase-XX-complete`.
4. If anything fails, **stop**, report exactly what is missing, and fix it through a follow-up PR from the same feature branch. Never fix on `main`.

## 6. Phase Review Report (sent at every PR stop)

- PR URL and branch name
- A file tree of the changes
- New dependencies (with versions) and why each is needed
- New endpoints, configuration, and infrastructure
- Test results: counts per test type, the smoke test result, and a link to `docs/test-reports/phase-XX.md`
- Every **Done when** item → ✅ with how it was verified, or ⚠️ with the manual steps
- **What you should review:** 3–5 files or areas
- **Concepts to understand:** 2–4 sentences per concept, tied to the code written
- **How to try it yourself:** exact commands
- A reminder: *"Review the PR, merge it with 'Create a merge commit', run `/clear`, then type `merged, continue`."*

## 7. Mandatory stop points

- After the PR is raised (every phase).
- Any step needing the user's credentials, accounts, or money. These are listed under "Your manual steps" in each phase file. Give exact steps and wait for `done`.
- A failed pre-flight or merge verification.
- A dependency or version conflict that cannot be solved within the phase's scope.
- Tests still failing after reasonable attempts. Never disable or delete tests to get a green build.
- Any situation where a Git rule would have to be broken.

Before **every** stop: update and commit `CURRENT.md` (and push if a branch exists).
