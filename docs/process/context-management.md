# 🧠 Context Management

Goal: every session starts small and resumes exactly where the last one stopped. **State lives in files and Git, never in the conversation.**

## What Claude Code loads per session

| File | When | Budget |
|---|---|---|
| `CLAUDE.md` | Always (auto-loaded) | Short, rules only |
| `docs/process/execution-protocol.md` | Every session start | ~1 page |
| `docs/progress/CURRENT.md` | Every session start | Max ~60 lines |
| `docs/progress/RECENT.md` | Every session start | 2 summaries, max ~30 lines each |
| `docs/phases/phase-XX-*.md` | Current phase only | 1 file |
| `docs/process/testing-protocol.md` | At the testing step | On demand |
| `docs/process/git-workflow.md` | At pre-flight, PR, and verification | On demand |
| `docs/decisions.md` | When touching an area with earlier decisions (search it with `grep`) | On demand |
| `docs/architecture/target-architecture.md` | Phases 19–29 only | On demand |
| `docs/progress/archive/*` | Only if an old phase detail is truly needed | Rarely |
| Other phase files, `docs/ROADMAP.md` in full | **Do not read** | n/a |

## `CURRENT.md`: the in-phase checkpoint

- Tracks the current phase only. It is reset at every phase start (housekeeping commit).
- **Update it when:** the branch is cut, a checklist item is completed, tests are run, a decision is made, a blocker appears, and before every stop.
- **Commit it** together with the related work (or as `chore(progress): checkpoint` when there is no other change). Push whenever a branch exists.
- The **Next action** line must always be specific enough that a fresh session can continue without any conversation history. Example: "Implement `OrderAuditService` with `REQUIRES_NEW`, then write the concurrency test in `OrderConcurrencyIT`."

## `RECENT.md`: a rolling window of the last two phases

- At step 7 of each phase, write that phase's summary at the **top** of `RECENT.md`, using the template in the file.
- If `RECENT.md` now holds more than 2 summaries, **move the oldest** to `docs/progress/archive/phase-XX-summary.md`. Nothing is deleted; it is just no longer loaded.
- A summary contains facts a next phase needs: what exists now, key classes and packages, config and ports, gotchas, and follow-ups. It does not contain a narrative of the work.

## `decisions.md`: long-lived knowledge

- One or two lines per decision: `[Phase XX] Decision: … Reason: …`.
- Decisions never expire. Search them with `grep` instead of loading the whole file once it grows.

## The code is the source of truth

Read the code when you need details. Don't rely on memories of earlier conversations.

## Keeping the conversation lean

- Don't print full files, full logs, or long diffs into the conversation. Use `grep`, `tail`, `git diff --stat`, and targeted `view` ranges.
- After auto-compaction, or whenever state feels uncertain, rerun the resume sequence (execution-protocol section 1) before acting.
- For a long phase, suggest the user runs `/clear` at a safe point after committing the checkpoint.

## Recommended user habit

Run `/clear` at every phase boundary: merge → `/clear` → `merged, continue`. The PR stop is always a clean checkpoint.
