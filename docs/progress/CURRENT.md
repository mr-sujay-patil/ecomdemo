# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-04
- **Fix:** KI-081 (a new account's concurrent first cart requests race on `uq_cart_user`; the loser gets `500`). Reported by the web team (web KI-041, web PR #61 red on it); the owner chose "Fix it in the backend" 2026-11-04
- **Branch:** fix/ki-081-cart-create-race (cut from `main` at `b712df7`, the merge of PR #109)
- **Step:** IN_PROGRESS: red, fix and targeted tests done; full `./mvnw -B verify` running
- **Waiting for user:** no
- **History:** KI-073 (PR #109) merged as `b712df7`, merge-verified 2026-11-04 (tip `3bc9057` in `main`; `./mvnw -B -pl ecomdemo-app -am test` on `main`: 376 tests, 0 failures). Tags for the owner (403 here): `ki-073-fixed` `b712df7`, and those listed before

## Checklist
- [x] KI-081 In progress
- [x] Cause (read and reproduced): `CartService.currentCart()` = `findByUserId(...).orElseGet(() -> save(new Cart(userId)))`; `cart` is `id` identity + `user_id` NOT NULL with `uq_cart_user`. Two concurrent first requests both insert; the loser's `DataIntegrityViolationException` is a `500`
- [x] Red first (`70d7a1d`): `CartApiIT.concurrentFirstRequestsShareOneCart` (10 fresh accounts, 6 concurrent first requests each, one of them an add): red on `main`'s code, account 0 got `[200, 200, 200, 200, 500, 200]`
- [x] Fix (`4f04394`, `7b70191`): `CartRepository.createIfAbsent` = `insert into cart (user_id) values (?) on conflict (user_id) do nothing`, then `findByUserId`. PostgreSQL-only (H2 does not parse it: the H2 repository test first added for it failed with a syntax error and was removed); `CartServiceTest` updated (+1 test)
- [x] Targeted: `Cart*Test` 32 tests, `CartApiIT` 10 of 10 (the new one included), green
- [ ] Full `./mvnw -B verify` once before the PR
- [ ] Docs: decisions `[Fix KI-081]` done; KNOWN_ISSUES to Fixed with the PR number
- [ ] Push, PR; CI green; STOP for the owner

## Next action
When the full verify is green: open the PR (KI-081 Fixed with its number), wait for CI, STOP for the owner. Then the web's PR #61 moves its pin to the merge commit.

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
