# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 12: Code Quality
- **Branch:** feature/phase-12-sonarqube
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Phase 11 merge verification (passed 2026-09-22, after a follow-up PR)
PR #11 MERGED (95fd430, 2 parents) and follow-up PR #13 MERGED (5b50c6b, 2 parents: 5748b56 +
365a9ca); branch is an ancestor of `main`; no branch commits missing from `main`; the only file
difference is `main` being AHEAD (Dependabot's `setup-buildx-action@v4` from PR #12); branches
intact; `.github/workflows/ci.yml`, `.github/dependabot.yml` and the corrected
`docs/test-reports/phase-11.md` all in `main`.
**CI on `main` is green** (run 35697500349, both jobs) — required from this phase onwards — and
the publish now works end to end: `latest` and `sha-5b50c6b` both exist in GHCR.
`./mvnw clean verify` on `main` -> 190 + 30, 0 failures, 0 skipped, 41.9 s.
`docker compose up -d --build` -> both services healthy; `scripts/smoke-test.sh` -> 125 passed,
0 failed, 0 skipped, 0 ERROR. Tag `phase-11-complete` pushed at 5748b56.

## Checklist (copied from the phase's "What you'll implement")
- [x] SonarQube Community Edition in `compose.sonar.yaml`
- [x] The JaCoCo plugin, with analysis through `./mvnw sonar:sonar`
- [x] Fix the reported bugs, smells and hotspots
- [x] A quality gate (e.g. >= 70% coverage on new code, no new critical issues)
- [x] The project passes the quality gate, and the results are documented (the "Done when")
- [x] Smoke test: no new checks, but the existing 125 must still pass
- [x] Testing protocol run in full + docs/test-reports/phase-12.md
- [x] README section, decisions.md, RECENT.md rotation (Phase 10 archived), tracker -> 🔵
- [ ] PR raised

**Optional, NOT in scope (hard rule 7):** SonarQube Cloud in GitHub Actions with PR decoration.
Suggest it in the PR; do not build it. That also means the user needs no SONAR_TOKEN.

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS, 190 + 30, 0 failures, 0 skipped. Run after
  every change in this phase. No test added, removed or disabled.
- 2026-09-22: JaCoCo merged report -> 96.5% instruction, 97.3% line, 81.0% branch, 100% class.
  Both exec files present and merged (`jacoco-unit.exec` + `jacoco-it.exec` -> `jacoco-merged`).
- 2026-09-22: SonarQube first analysis -> 16 issues, 92 min debt, reliability D, security D.
  After the fixes -> **0 bugs, 0 vulnerabilities, 0 smells, 0 hotspots, 0 min debt, A/A/A**.
- 2026-09-22: `-Dsonar.qualitygate.wait=true` -> **QUALITY GATE: OK**, every condition green.
- 2026-09-22: `scripts/sonar-setup.sh` proven against a VIRGIN server (`down -v` + fresh `up`):
  created the project, the gate and the two custom conditions; re-running it is all no-ops.
- 2026-09-22: `scripts/smoke-test.sh` against the compose stack -> 125 passed, 0 failed,
  0 skipped, 0 ERROR in the container log. The script is unchanged this phase, as specified.

## Open issues / blockers
- none. All three opening concerns are settled: SonarQube is in its own compose file, JaCoCo
  merges both suites, and every reported issue is either fixed or (for the one review rule)
  suppressed in code with its reasoning attached.
- Worth knowing for next time: `sonarqube:lts-community` still resolves to 9.9 and dies
  mid-migration against PostgreSQL 18 — pin an exact `*-community` build.

## Decisions this phase (copied to docs/decisions.md ✅ — 12 entries)
- Two JaCoCo agents + a merge; a single prepare-agent would measure half the suite.
- Surefire/Failsafe argLines use @{...} late evaluation — a literal argLine overrides JaCoCo's
  and coverage silently reads 0%.
- SonarQube in its own compose file, with its own PostgreSQL, pinned to an exact community build.
- The quality gate is created by a script so it survives `down -v` and lives in Git.
- Every gate condition is on NEW code; new_coverage stays at 80 (the project is at 97.4%).
- java:S4502 (CSRF) suppressed in code, not "accepted" in the server — a server resolution is
  lost on rebuild and invisible in review.
- `record` -> `recordAttempt`; `throws Exception` removed (verified by compiling, not trusted).
- SonarQube Cloud + PR decoration NOT built (optional; hard rule 7) — suggested in the PR.

## Environment left behind
Docker Desktop is RUNNING. Two stacks are up:
- the application (`ecomdemo-app`, `ecomdemo-db`), healthy, schema v6;
- SonarQube (`ecomdemo-sonarqube`, `ecomdemo-sonar-db`) at http://localhost:9000 with this
  branch's analysis. `docker compose -f compose.sonar.yaml down` stops it, `-v` deletes history.
The SonarQube admin password and the analysis token exist only on this machine, outside the
repository — regenerate a token from My Account -> Security if a fresh session needs one, or wipe
and re-run `scripts/sonar-setup.sh`.
The pre-compose container `ecomdemo-postgres` is stopped, not deleted. `.env` exists locally with
a real JWT_SECRET and is gitignored. No stray Java processes.

## Still outstanding (user, not blocking)
- **`required_status_checks` on `main` is `null`.** CI reports but does not block; PRs #11 and #12
  were both merged with a red run. Phase 11's stated manual step, still undone.

## Next action
Raise the PR (`gh pr create --base main`), wait for CI to go green, then STOP for the user's
review. From Phase 11 onwards a PR may be merged only when CI is green.
