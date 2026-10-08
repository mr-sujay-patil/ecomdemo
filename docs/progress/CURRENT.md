# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-053, HIGH CVE-2026-106451 in lz4-java 1.10.1 turns the image scan red (blocks PR #78 and publish)
- **Branch:** fix/ki-053-lz4-java-cve (cut from `main` at `33d6f1a`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-053 PR)

## Also open: PR #78 (KI-052, branch fix/ki-052-remove-unused-inventory-reserve-release)
Approved by the user ("approved, merge it") but NOT merged: its Image scan is red because of this CVE (workflow rule 9).
After KI-053 merges: update #78's branch from `main` (merge commit, no rewrite), wait for green, merge --merge, tag `ki-052-fixed`.

## Done
- `lz4-java.version` 1.11.4 + a `dependencyManagement` entry in the parent pom (Boot does not manage it; kafka-clients brings it).
  `dependency:tree` shows 1.11.4. KNOWN_ISSUES KI-053, `security.md`, `decisions.md` [Fix KI-053].
- `./mvnw -B verify` green. The proof is the CI Image scan on this PR (it said `Total: 1 (HIGH: 1)` per affected image before).

## Next action
Wait for this PR's CI. Green: ask for approval. After `approved, merge it`: merge --merge, verify in `main`, tag `ki-053-fixed`, then #78 as above.

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
