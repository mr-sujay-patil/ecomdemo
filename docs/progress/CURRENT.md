# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-01
- **Fix:** KI-039 — compose's Kafka keeps nothing across `down`/`up`
- **Branch:** fix/ki-039-kafka-data-volume (cut from `main` at `f1a9547`)
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification before this fix — PASSED (tag `phase-33-complete` on `f1a9547`)
- Phase 33: PR #54 merge commit (2 parents). 0 missing commits, 0 diffs, branch alive locally and on
  GitHub. On main: `./mvnw clean verify` 697 tests (521 unit, 176 IT), 0 failed; cold smoke
  (`down -v`) 467/0/0.
- CI on main (run 36761772562) green on attempt 2: attempt 1's `Publish image to GHCR` was cancelled
  mid-upload (one layer stuck at 0 B for ~8 min, no code involved); `gh run rerun --failed` passed.

## Checklist (KI-039 has a register row only; scope derived from it)
- [ ] Reproduce first: `KafkaStorageConfigTest` (compose + k8s parity) and `ComposeKafkaPersistenceIT`
      (boots the broker from compose.yaml's own definition, removes the container, restarts it on the
      same volume) - both red on main
- [ ] Fix: compose's kafka gets `KAFKA_LOG_DIRS` = the volume's mount (k8s already has it)
- [ ] Smoke: the live broker's log directory is the mounted volume (both platforms)
- [ ] By hand: `down`/`up` without `-v` keeps topics, messages and a group's offset (numbers in the PR)
- [ ] Survey the other named volumes for the same class of bug; log findings, do not fix them here
- [ ] Docs: README, development-environment.md if it says anything, `decisions.md`, KI-039 → Fixed
- [ ] Testing protocol steps 1, 3, 4, 7, 8 + regression tests; PR; PR number in the KI status

## Notes for this fix
- k8s/data/kafka.yaml already sets `KAFKA_LOG_DIRS=/var/lib/kafka/data`; only compose.yaml is wrong.
- The image's `/var/lib/kafka/data` is owned by appuser (uid 1000), so a named volume there is writable.
- The image's launch script tolerates "already formatted", so a restart on a populated volume works.
- Side effect to check, not to fix: KI-040 (dead-letter replay audit keyed on the Kafka address) was
  reachable because offsets restarted; with a persistent broker a kept-volume smoke should pass.

## Next action
Housekeeping committed. Commit the two red tests (already written, untracked in
ecomdemo-app/src/test/java/com/ecomdemo/: ComposeKafka, KafkaStorageConfigTest,
ComposeKafkaPersistenceIT), then add `KAFKA_LOG_DIRS` to compose.yaml's kafka environment.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- Verification uses a COLD stack (`down -v`, `up --build --wait`, `.smoke-state` removed).
- kind: `SKIP_BUILD=1 scripts/k8s-up.sh` does NOT restart pods; `kubectl rollout restart` all Deployments.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy. Never print `.env`.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.
- The user's `.env` now holds JWT_SIGNING_KEY and the three *_CLIENT_SECRET values (Phase 33).

## ⚠️ Carried, not fixed
Tracked in `docs/KNOWN_ISSUES.md` (open rows: KI-002..011, KI-040, and the triage sections).
