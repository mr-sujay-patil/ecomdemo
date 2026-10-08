# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-007, `GET /api/products` returns the whole catalogue, unpaginated
- **Branch:** fix/ki-007-paginate-products (cut from `main` at `8fb9a01`)
- **Step:** PR_OPEN
- **PR:** TBD
- **Waiting for user:** YES (review of the KI-007 PR)

## Merge verification of KI-006 (done 2026-10-08)
PR #69 merged as `8fb9a01`; CI on `main` green incl. scans and publish; tag `ki-006-fixed`.

## Decision (user, 2026-10-08)
Array body + headers (`X-Total-Count`, `Link`); `page` from 0, `size` default 50, max 100 (else 400). Behaviour change: no
parameters means the first 50.

## Done
- catalog-service: `ProductService.findPage` (cache per page `p<page>:<size>`, every write clears `productList`), `ProductPage`
  record, controller params + headers; `CatalogClient.findAll()` follows pages; gateway CORS exposes the headers; Gatling
  `TestData` follows pages. Tests: `ProductApiIT` (seen failing first), `ProductControllerTest`, `ProductServiceTest`,
  `CatalogClientPagingTest`, `CorsIT`, `ProductCacheEvictorTest`, `CacheApiIT`. Smoke: paging checks + `listing_all` helper.
- Docs: `security.md` API4, `decisions.md` [Fix KI-007], README endpoint table + cache table, KNOWN_ISSUES.
- `./mvnw clean verify` exit 0; kind smoke 447/0/9 (also first run of KI-006's 8088 probe in k8s).
- NOT run: compose smoke (the frontend team's stack, ~/projects/ecomdemo-backend-readonly, held the container names; user chose kind).

## Next action
STOP: wait for the user's review. After `approved, merge it`: wait for the required check, merge with `--merge`, verify (tip in
`main`, CI on `main` incl. scans and publish), tag `ki-007-fixed`. Still open: KI-008..011, KI-047.
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
