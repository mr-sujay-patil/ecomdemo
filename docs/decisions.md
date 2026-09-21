# Decision Log

> One or two lines per decision. Newest at the bottom. Format:
> `[Phase XX] Decision: … | Reason: … | Alternatives considered: …`

[Phase 00] Decision: Merge commits only, no branch deletion | Reason: preserves full phase history and enables ancestry-based merge verification | Alternatives considered: squash merge (rejected: breaks verification)
[Phase 00] Decision: Per-phase docs with a checkpoint file | Reason: keeps each Claude Code session's context small and resumable | Alternatives considered: single roadmap file
