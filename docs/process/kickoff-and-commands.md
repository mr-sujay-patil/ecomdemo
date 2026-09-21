# 🚀 Kickoff Prompt & Commands

## Kickoff prompt (paste once, into a fresh Claude Code session in the project folder)

```
Follow CLAUDE.md strictly. Run the resume sequence from docs/process/execution-protocol.md, determine the current state from Git and docs/progress/CURRENT.md, and start executing the roadmap from the current phase (Phase 0 if nothing exists yet).

Work one phase at a time: feature branch from main → implement only the current phase file → full testing protocol → PR to main → STOP with the Phase Review Report.

Never merge unless I say "approved, merge it". Never commit to main after the Phase 0 bootstrap. Never delete any branch. Never force-push. Keep docs/progress/CURRENT.md updated and committed at every step so any new session can resume exactly where you stopped.

Begin.
```

## Commands during the journey

| You type | Claude Code does |
|---|---|
| `merged, continue` | Verifies the merge in `main`, re-tests on `main`, tags the phase, and starts the next phase |
| `merged, stop` | Verifies and tags, then stops |
| `approved, merge it` | Merges with a merge commit, then continues |
| `changes: <feedback>` | Fixes on the same branch, re-tests, updates the PR, and stops again |
| `plan first` | Shows a plan for the next phase and waits for approval |
| `status` | Reports where things stand, without changing anything |
| `continue` | Resumes from the Git state and `CURRENT.md` (use after `/clear`, a restart, or a crash) |
| `stop` | Saves a checkpoint and stops |
| `done` | Confirms that a manual step it asked for is complete |

## Recommended rhythm

Review PR → merge (merge commit) → `/clear` → `merged, continue`
