# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-005, no load shedding at checkout (requests wait 10 s for a DB connection instead of a fast 503 + `Retry-After`)
- **Branch:** fix/ki-005-checkout-load-shedding (cut from `main` at `4ce3159`)
- **Step:** PR_OPEN
- **PR:** see the PR for this branch (number is in KNOWN_ISSUES once set)
- **Waiting for user:** YES (review of the KI-005 PR)

## Merge verification of KI-004 (done 2026-10-08)
PR #67 merged as `4ce3159`; branch ancestor of `main`, diff empty, `./mvnw clean verify` exit 0, CI on `main` green incl. scans
and publish; tag `ki-004-fixed`; cold compose smoke on `main` 484/0/0.

## Done
- `OrderService.place()` takes a permit from bulkhead `checkout` (8 concurrent, no wait); otherwise 503 + `Retry-After: 1`,
  outcome `shed` (new `CheckoutOutcome.SHED`), nothing written. `resilience4j.bulkhead.instances.checkout.*` in app properties.
- `CheckoutLoadSheddingTest` reads the shipped limit; seen failing with the check removed. `OrderServiceTest` given a registry.
- Docs: `security.md`, `performance.md`, `decisions.md`, KNOWN_ISSUES row.
- `./mvnw clean verify` exit 0; compose smoke on this tree 484/0/0. Not run: Kubernetes; the Phase 30 spike against the new limit.

## Next action
STOP: wait for the user's review. After `approved, merge it`: wait for the required check, merge with `--merge`, verify (tip in
`main`, CI on `main` incl. scans and publish), tag `ki-005-fixed`. Suggested, not built: re-run the Phase 30 spike to measure
the limit of 8; a Grafana panel for `shed`. Still open: KI-006..011. `stash@{0}` (KI-045 noise) is the user's to drop.

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
