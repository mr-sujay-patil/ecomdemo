# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-16
- **Fix:** KI-077 (compose does not forward `CORS_ALLOWED_ORIGINS` to the gateway); approved 2026-10-16 ("Log and fix CORS gap")
- **Branch:** fix/ki-077-compose-cors-origins (cut from `main` at `11e6240`)
- **Step:** BRANCHED
- **Waiting for user:** NO
- **History:** PR #99 (KI-074, KI-075) merged as `e0a13eb`, merge verification passed 2026-10-16; the owner pushes `ki-074-fixed` and `ki-075-fixed` (tag pushes are refused here, 403)

## Checklist
- [ ] Regression test `scripts/test-compose-cors.sh` (red first)
- [ ] `compose.yaml`: forward `CORS_ALLOWED_ORIGINS` to `gateway-service`; `.env.example` documents it
- [ ] Testing protocol (steps 1, 3, 4, 7, 8 + the regression test); live preflight check (5173 allowed, 3000 refused)
- [ ] KNOWN_ISSUES Fixed, decisions `[KI-077]` if the fix changes what a doc says
- [ ] Push, PR, CURRENT to PR_OPEN

## Next action
Write `scripts/test-compose-cors.sh` modelled on `scripts/test-compose-ports.sh`; run it and see it fail; then fix `compose.yaml`.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- Windows reserves TCP 2906-3005 (so 3000) and 9014-9113: Grafana is on host 13000 (`GRAFANA_PORT` in `.env`), Prometheus on 19090.
- Local, untracked `compose.override.yaml` (in `.git/info/exclude`) forwards `CORS_ALLOWED_ORIGINS` until KI-077 lands; delete it after the merge.
- The frontend team's `webstack-*` stack is gone; the frontend now uses this backend only (gateway `:8080`).
- `scripts/k8s-up.sh` loads images but does NOT restart pods: `kubectl -n ecomdemo rollout restart deploy` after it.
