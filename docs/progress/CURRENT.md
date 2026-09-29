# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-29
- **Phase:** 31 — Security Scanning (OWASP Dependency-Check + Trivy)
- **Branch:** feature/phase-31-security-scanning (cut from `main` at `387d755`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #48 https://github.com/mr-sujay-patil/ecomdemo/pull/48
- **Waiting for user:** YES - review of the Phase 31 PR

## Merge verification before this phase — PASSED
- Phase 30: PR #47 merged as `387d755` (merge commit, 2 parents). 0 missing commits, 0 diffs, branch alive
  locally and on GitHub. CI on main green (run 36539654692). `verify` on main 616/0/0/0. Cold compose
  (down -v, --build, `.smoke-state` removed) smoke on a COPY 404/0/0 (user's `.env`). Tagged
  `phase-30-complete`.

## Design (decided)
- Dependency-Check 13.0.0 in root pluginManagement (not bound to a phase): failBuildOnCVSS 7,
  skipTestScope, suppression file with notes+until, OSS Index/Node/RetireJS/.NET analyzers off,
  NVD key from env `NVD_API_KEY`. CI job `dependency-scan`: fails fast if the secret is missing,
  NVD data cached (unique key + restore-keys), `package -DskipTests` then `aggregate`.
- CI job `image-scan`: builds all 8 images in ONE job (build stage reused), Trivy 0.74.0 pinned by
  DIGEST via docker, HIGH/CRITICAL fail (ignore-unfixed NOT used), `.trivyignore.yaml` with
  statement+expired_at, CycloneDX SBOM per image uploaded. `publish` needs both scans.
- Scan findings (Trivy): Tomcat 11.0.24 3x CRITICAL, jackson-databind 3.1.5/2.21.5 HIGH -> fixed by
  overriding Boot-managed versions (tomcat 11.0.25, jackson 3.1.6 / 2.21.6); all 8 images clean.

## Checklist (from the phase file's "What you'll implement")
- [x] Dependency-Check in CI (fail on high severity) - `d5c6f37`; secret NVD_API_KEY set from .env; 2 false positives suppressed (`cd3f02a`)
- [x] Trivy image scans in CI (`d5c6f37`; local run of the exact steps: 8/8 clean)
- [x] Every finding fixed or suppressed with a justification (Tomcat/Jackson fixed `5430dd0`; Kotlin/pgvector suppressed)
- [x] An OWASP API Top 10 review in `docs/security.md`. API5 found AND fixed (user said fix it): `4671fc1`, catalog writes ADMIN|SERVICE, inventory ADMIN|SERVICE; re-probed on rebuilt compose: all 403, reads 200
- [x] Done when: CI blocked commons-text 1.9 on PR #48 (run 36555557687 red, revert 36556218892 green); security.md complete
- [x] Testing: verify 620/0/0/0; compose cold 404/0/0; k8s 360/0/7; report, README, decisions (7), RECENT (Phase 29 archived), tracker 🔵, PR #48

## Next action
STOPPED at PR #48 (opened as a draft for the CI demonstration, now ready), waiting for the user. Do
NOT merge unless the user says `approved, merge it` (`gh pr merge 48 --merge`). On `merged, continue`:
merge verification (git checks, CI on main incl. both scans, `verify`, cold compose smoke on a COPY),
tag `phase-31-complete`, then Phase 32 per ROADMAP. Note: commit 386cacb "placeholder" is the revert
of 9065a72 (mistaken --amend, explained in 9484507 and the test report).

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification still uses a COLD stack.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy.
- The persistence probe makes the smoke count path-dependent: +1 check with kept volumes.
- The user's `.env` sets AI_CHAT_PROVIDER=ollama and AI_EMBEDDING_PROVIDER=ollama (host Ollama:
  llama3.2, nomic-embed-text; RTX 5070 Ti). Never print `.env`.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).

## ⚠️ Carried, not fixed (oldest first)
- A saga whose event is dead-lettered leaves the order PENDING; no timeout or reconciliation yet.
- The CSV import is a distributed write with no shared transaction (restartable, idempotent).
- **HS256 with a shared secret** — every service can mint as well as verify.
- `ecomdemo-app` is not yet named `order-service`.
- catalog-service has no springdoc; the gateway does no request logging.
- The gateway's `/api/products` route has no breaker; inventory calls have no resilience policy.
- Phase 21 recorded no entries in `docs/decisions.md`; `application.properties` still describes the
  removed customer proxy.
- catalog-service is slow on its first requests after a restart (a half-open trial can exceed 500 ms).
- k8s: the app must stay at 1 replica (no leader election); observability not in the cluster.
- Phase 27/28: OpenAI chat and embeddings untested against a real key (none here); Ollama verified for real.
- In this Claude shell `grep` is a broken Claude Code wrapper function: use `command grep`.
