# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-09
- **Fix:** KI-084 + KI-085 + KI-086 together (the published OpenAPI contract: no response schema lists `required`; a stale `unitPrice` text; a nullable object published as "null only"). Reported by the web team (web KI-016, KI-019, KI-021); the owner: "do the OpenAPI fixes" (2026-11-09)
- **Branch:** fix/ki-084-085-086-openapi-contract (cut from `main` at `aa5da39`)
- **Step:** IN_PROGRESS: red, fix and targeted tests done; full `./mvnw -B verify` next. The PR waits for PR #117 (KI-036, another session's) to merge: one open PR at a time
- **Waiting for user:** no
- **History:** KI-062 (PR #115) merged as `aa5da39`. PR #117 (fix/ki-036-meter-tag-budget) is open from another session; this branch touches none of its files

## Checklist
- [x] Audit: every response record read with its factory; six fields could be null without saying so (`OrderResponse.statusReason`, `OrderStatusResponse.reason`, `JobExecutionResponse.startTime`, `endTime`, `failureMessage`, `ProductImportResponse.errorFile`); `CustomerResponse.fullName` is `NOT NULL`; `exitCode` comes from `ExitStatus`, never null
- [x] Red first (`2a164d3`): `OpenApiDocumentationTest` 2 new tests red on `main` (`ApiError.status is required`; `unitPrice` says "not a snapshot"); `PublishedApiDocs.assertResponseSchemasAreExact` now runs in every service's docs test; assistant `ApiDocsIT` checks `pendingAction` is `anyOf`
- [x] Fix (`1c6f4ff`): `ResponseSchemaContract` (common, an `OpenApiCustomizer` bean from `OpenApiConfig`); six fields `nullable = true`; the `unitPrice` text. `ResponseSchemaContractTest` 3, `OpenApiDocumentationTest` 23, green
- [x] Docs: KNOWN_ISSUES KI-084, 085, 086; decisions `[Fix KI-084, KI-085, KI-086]`
- [ ] Full `./mvnw -B verify` (the service ITs, the assistant's `pendingAction` included)
- [ ] PR once #117 has merged (merge `main` in first); CI green; STOP for the owner. Then the web pins the merge and drops its `PendingCartAddition | null` widening (web KI-021) and regenerates its types (web KI-016)

## Next action
Run the full `./mvnw -B verify` (Docker for the ITs). Then, when PR #117 is merged, merge `main` into this branch and open the PR.

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
