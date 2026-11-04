# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-03
- **Fix:** KI-076 + KI-079 together (workflow rule 11: same code, the cert-reload scripts and their renewal ITs). KI-076: `PostgresTlsIT.renewalIsReloaded` times out (CI again on PR #107, 2026-11-03). KI-079: `KafkaTlsIT.renewalIsReloaded` timed out (PR #102). Started on the owner's "keep working" (2026-11-03) after KI-080
- **Branch:** fix/ki-076-079-cert-reload-race (cut from `main` at `f872e3d`)
- **Step:** PR_OPEN (PR #108)
- **Waiting for user:** YES: CI on PR #108, the k8s smoke (owner's machine), then `approved, merge it`
- **History:** KI-080 (PR #107) merged as `f872e3d`, merge-verified 2026-11-03 (push run 316 on `main`: image scan, dependency scan and publish green; images published again). Tags for the owner (403 here): `ki-080-fixed` `f872e3d`, `ki-078-fixed` `d69b738`, `ki-077-fixed` `8d36b19`, `ki-074-fixed`/`ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`

## Checklist
- [x] KI-076 and KI-079 In progress
- [x] Cause (read, 2026-11-03): each cert-reload script hashes only `tls.crt`; the ITs copy `tls.crt` and then `tls.key`. A poll between the two installs the new certificate with the OLD key: PostgreSQL rejects the pair and keeps the old one, but `pg_reload_conf()` returns t, so the script logs "reloaded" and never retries (the hash no longer changes) -> the 30 s window runs out. Kafka validates a dynamic keystore update with a handshake, so there the race retries; KI-079's cause is not shown by the log (the test reads reload.log only after success)
- [x] Red first (`74404af`): config tests 7 of 7 red on `main`; `PostgresTlsConfigTest`/`KafkaTlsConfigTest` check the change marker covers the key (local); `PostgresTlsIT` copies the certificate, waits for a reload attempt, then the key (deterministic red with the old script, CI only)
- [x] Fix (`da5c7d5`): the marker is `cat "$key" "$cert" | sha256sum` in the 6 Postgres manifests and kafka.yaml; both ITs put reload.log in a timeout's message
- [x] Tests: config tests 26/26 and 9/9; the real script against stub install/psql: `main`'s ends on (old key, new cert), the fix's on (new, new); `sh -n` on all 7 scripts; `./mvnw -pl ecomdemo-app -am test`: 0 failures, the 29 Docker-only errors as before
- [x] Docs: KNOWN_ISSUES (both Fixed), decisions `[Fix KI-076, KI-079]`
- [x] Push, PR #108 (https://github.com/mr-sujay-patil/ecomdemo/pull/108)
- [ ] CI green on PR #108 (`Build and test` runs both renewal ITs, two-step); the k8s smoke is the owner's (manifests changed; no kind here)

## Next action
Wait for CI on PR #108; fix it on this branch if red (a renewal IT timeout now prints the script's log); add the results to the PR description; STOP for the owner, who also runs the k8s smoke. After the merge: merge verification; the owner pushes `ki-076-fixed` and `ki-079-fixed` (403 here).

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
