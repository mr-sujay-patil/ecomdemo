# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-21
- **Fix:** KI-077 (CI's image scan red on `main` since 2026-10-18: Alpine `libexpat` 2.8.5-r0, CVE-2026-77214 HIGH, fixed in 2.9.0-r0; the runtime base image `eclipse-temurin:21-jre-alpine` was last pushed 2026-09-25); approved 2026-10-21 (the owner: "go with your recommended approach")
- **Branch:** fix/ki-077-alpine-os-packages (cut from `main` at `11e6240`)
- **Step:** PR_OPEN (PR #101, label `run-smoke`)
- **Waiting for user:** YES: CI on PR #101 (image scan, build and test, compose smoke), then review / `approved, merge it`
- **History:** KI-074 and KI-075 (PR #99) merged as `e0a13eb` and merge-verified 2026-10-21 (branch tip `5fda842` in `main`; push CI on `main`, run 281, green). Tags `ki-074-fixed`, `ki-075-fixed` (`e0a13eb`) and `phase-37-complete` (`4767124`) are the owner's to push (403 here)

## Checklist
- [x] KI-077 recorded in `docs/KNOWN_ISSUES.md` (In progress)
- [x] Regression test red first (`f7b0bf6`): `DockerfileRuntimePackagesTest` 2 of 2 failed on `main`'s Dockerfile and ci.yml
- [x] Fix (`9dbb3d8`): `RUN apk upgrade --no-cache` at the top of the runtime stage; ci.yml publish `no-cache-filters: runtime`; the test 2/2 green
- [x] Tests here: `./mvnw -B test` (all modules, failures ignored to see every module): 688 unit tests, 0 failures, 29 errors, all in the 4 Testcontainers classes (CatalogSchemaTest, ConcurrentReservationTest, InventorySagaTest, PaymentServiceTest: "Could not find a valid Docker environment"; no Docker in this container). ITs, the full verify and the image scan run in CI
- [x] Docs: KNOWN_ISSUES (Fixed), decisions `[Fix KI-077]`, `docs/security.md` (findings row, base-image paragraph)
- [ ] Push, PR, CURRENT to PR_OPEN; CI green
- [x] Push, PR #101 (label `run-smoke`), CURRENT to PR_OPEN
- [ ] CI green on PR #101: `Image scan (Trivy)` clean proves the fix; `Build and test` runs the full verify with ITs

## Next action
Wait for CI on PR #101; fix it on this branch if red. Then STOP for the owner. After the merge: merge verification (section 5; the push run on `main` is the scans and the publish, which this fix changes, so wait for it to be green); the owner pushes `ki-077-fixed` (tag pushes are refused here, 403), plus `ki-074-fixed`, `ki-075-fixed` (`e0a13eb`) and `phase-37-complete` (`4767124`).

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
