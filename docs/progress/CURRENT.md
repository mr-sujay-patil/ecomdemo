# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-007, `GET /api/products` returns the whole catalogue, unpaginated
- **Branch:** fix/ki-007-paginate-products (cut from `main` at `8fb9a01`)
- **Step:** BRANCHED
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification of KI-006 (done 2026-10-08)
PR #69 merged as `8fb9a01`; branch ancestor of `main`, diff empty, `./mvnw clean verify` exit 0, CI on `main` green incl. scans
and publish; tag `ki-006-fixed`.

## Decision (user, 2026-10-08)
Array body + headers: the body stays a bare JSON array (the web team's client keeps working); optional `page`/`size`, a default
and a maximum size, `X-Total-Count` and `Link` headers (exposed through CORS). The default size is the one behaviour change.

## Checklist
- [ ] Reproduce first: a test that fails because the list is unbounded
- [ ] catalog-service: page/size (validated, default + max), headers, cache keys per page
- [ ] gateway: CORS exposes the new headers; route passes them
- [ ] smoke test, perf tests / scripts that read the list
- [ ] Docs: `security.md` API4, `decisions.md` [KI-007], OpenAPI text, KNOWN_ISSUES row -> Fixed
- [ ] Testing protocol (steps 1, 3, 4, 7, 8 + regression test)

## Next action
Read `ProductService.findAll` + cache config, then write the failing test. Still open: KI-008..011, KI-047.
`stash@{0}` (KI-045 noise) is the user's to drop.

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
