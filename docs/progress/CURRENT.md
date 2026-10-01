# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-01
- **Fix:** KI-039 — compose's Kafka keeps nothing across `down`/`up`
- **Branch:** fix/ki-039-kafka-data-volume (cut from `main` at `f1a9547`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #55 https://github.com/mr-sujay-patil/ecomdemo/pull/55
- **Waiting for user:** YES (review of PR #55)

## Merge verification before this fix — PASSED (tag `phase-33-complete` on `f1a9547`)
- Phase 33: PR #54 merge commit (2 parents). 0 missing commits, 0 diffs, branch alive locally and on
  GitHub. On main: `./mvnw clean verify` 697 tests (521 unit, 176 IT), 0 failed; cold smoke
  (`down -v`) 467/0/0.
- CI on main (run 36761772562) green on attempt 2: attempt 1's `Publish image to GHCR` was cancelled
  mid-upload (one layer stuck at 0 B for ~8 min, no code involved); `gh run rerun --failed` passed.

## Checklist (all done)
- [x] Reproduce first: `KafkaStorageConfigTest` + `ComposeKafkaPersistenceIT`, red on the unfixed config
      (IT re-proved red/green after its volume setup was corrected: Testcontainers' withFileSystemBind
      treats a name as a host path; a named volume needs setBinds(Bind(name, Volume(path))))
- [x] Fix: `KAFKA_LOG_DIRS: /var/lib/kafka/data` in compose.yaml (k8s already had it)
- [x] Smoke: the live broker's log directory is the mounted volume (compose and kind)
- [x] By hand: `down`/`up` keeps topic, end offset 3 and a group's offset 2 (real compose stack)
- [x] Survey of the other 12 named volumes: all hold real data, nothing to log
- [x] Docs: README volume passage, two decisions, KI-039 Fixed (PR #55), KI-040 annotated
- [x] Tests: verify 701 (524 unit, 177 IT) 0 failed; cold compose smoke 468/0/0; same smoke on KEPT
      volumes 470/0/0 (the Phase 32 failure scenario); kind 424/0/7 (cluster not redeployed: no
      manifest or image changed); cleaned up

## Notes for this fix
- k8s/data/kafka.yaml already sets `KAFKA_LOG_DIRS=/var/lib/kafka/data`; only compose.yaml is wrong.
- The image's `/var/lib/kafka/data` is owned by appuser (uid 1000), so a named volume there is writable.
- The image's launch script tolerates "already formatted", so a restart on a populated volume works.
- Side effect to check, not to fix: KI-040 (dead-letter replay audit keyed on the Kafka address) was
  reachable because offsets restarted; with a persistent broker a kept-volume smoke should pass.

## Next action
PR #55 is open. STOP: wait for the user's review. After the merge: merge verification (git checks,
CI on main, `./mvnw clean verify` + cold `down -v` smoke on main), tag `ki-039-fixed`. Then ask the
user what is next: KI-040 (dead-letter replay identity; now reachable only if a DLT's offsets restart),
or KI-002..011. The frontend does not need anything from this fix.

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
