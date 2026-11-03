# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-03
- **Fix:** KI-080 (CI's image scan red on `main` since 2026-10-28: `snappy-java` 1.1.10.7 through `kafka-clients`, CVE-2026-108106 and CVE-2026-93451, HIGH, fixed in 1.1.10.9); approved 2026-11-03 (the owner said "continue" on the proposed fix)
- **Branch:** fix/ki-080-snappy-java (cut from `main` at `d69b738`)
- **Step:** TESTING
- **Waiting for user:** no
- **History:** KI-078 (PR #102) merged as `d69b738`, merge-verified 2026-10-24 (push run 299 on `main` green). Tags for the owner (403 here): `ki-078-fixed` `d69b738`, `ki-077-fixed` `8d36b19`, `ki-074-fixed` and `ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`. Open Dependabot PRs #103 to #106 are untouched (#103, Boot 4.1.2, does not fix this: its Kafka 4.2.2 still declares snappy-java 1.1.10.7)

## Checklist
- [x] KI-080 recorded (In progress)
- [x] Reproduced: `./mvnw dependency:tree -Dincludes=org.xerial.snappy:snappy-java` resolved 1.1.10.7 in catalog, ecomdemo-app, inventory, notification, outbox, payment
- [x] Fix (`92cf7d9`): `snappy-java.version` 1.1.10.12, managed beside lz4-java, with its line in the override comment
- [x] Tests: the tree resolves 1.1.10.12 in all six; `./mvnw -B test`: 689 unit tests, 0 failures, 29 errors all in the 4 Testcontainers classes (no Docker here)
- [x] Docs: KNOWN_ISSUES (Fixed), decisions `[Fix KI-080]`, `docs/security.md` findings row
- [ ] Push, PR (label `run-smoke`: Kafka compression codec at runtime), CI green

## Next action
Push, open the PR `Fix KI-080: …` with label `run-smoke`, add its number in one more commit, wait for CI (the image scan must be clean on the 5 Kafka images), then STOP for the owner.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports (and has its own `ecomdemo-backend-readonly_kafka-data` volume: never touch it). kind does not conflict.
- compose ports are loopback-only now; probe from the LAN address (`hostname -I`) to check exposure.
- kind: `docker start ecomdemo-control-plane` revives the cluster; `scripts/k8s-up.sh` loads images but does NOT restart
  pods: `kubectl -n ecomdemo rollout restart deploy` after it, or the pods keep the old code.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy placed inside `scripts/` and delete it after. Never print `.env`.
- Flyway checksums comments too: never edit an applied migration; on a local DB roll back by hand if you must.
- Awaitility polls on its own thread: use `pollInSameThread()` when the condition needs the caller's transaction.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.
