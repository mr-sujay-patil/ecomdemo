# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-09
- **Fix:** KI-087 (KI-084's rule left nullable response fields out of `required`, but every key is always written: a client reads them as "may be missing"). Found by this session while moving the web's pin to `2ac9cdf`; part of the owner's "do the OpenAPI fixes" (2026-11-09)
- **Branch:** fix/ki-087-response-required-includes-nullable (cut from `main` at `2ac9cdf`, the merge of PR #118)
- **Step:** IN_PROGRESS: red and fix done, targeted tests green; full verify next
- **Waiting for user:** no
- **History:** KI-084/085/086 (PR #118) merged as `2ac9cdf` on the owner's `approved, merge it`, merge-verified 2026-11-09 (tip `8b1af75` in `main`, `main`'s tree equal to it; its full verify 974 tests, CI green). Tags for the owner (403 here): `ki-084-fixed`, `ki-085-fixed`, `ki-086-fixed` `2ac9cdf`, and those listed before

## Checklist
- [x] Cause: nothing omits nulls (no `NON_NULL`, `@JsonInclude` or inclusion property in any `src/main`), so every response key is always written; `required` must list nullable properties too
- [x] Red first (`fd0235a`): `ResponseSchemaContractTest.requiresEveryProperty` red on `main`; `PublishedApiDocs` and `OpenApiDocumentationTest` now expect every property required (`statusReason` required, typed `[string, null]`)
- [x] Fix: `schema.setRequired(every property)`; the `anyOf` rewrite for a nullable object stays. `ResponseSchemaContractTest` 3, `OpenApiDocumentationTest` 23, green
- [x] Docs: KNOWN_ISSUES KI-087; decisions `[Fix KI-087]`
- [ ] Full `./mvnw -B verify`; PR; CI green; STOP for the owner. Then the web pins it: regenerated types, no `assumeResponsePropertiesPresent`, no `PendingCartAddition | null` widening

## Next action
Run the full `./mvnw -B verify`, open the PR, set KI-087 to Fixed (PR #N).

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
