# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 12: Code Quality
- **Branch:** feature/phase-12-sonarqube
- **Step:** BRANCHED
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
- [ ] SonarQube Community Edition in `compose.sonar.yaml`
- [ ] The JaCoCo plugin, with analysis through `./mvnw sonar:sonar`
- [ ] Fix the reported bugs, smells and hotspots
- [ ] A quality gate (e.g. >= 70% coverage on new code, no new critical issues)
- [ ] The project passes the quality gate, and the results are documented (the "Done when")
- [ ] Smoke test: no new checks, but the existing 125 must still pass
- [ ] Testing protocol run in full + docs/test-reports/phase-12.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 10 archived), tracker -> 🔵
- [ ] PR raised

**Optional, NOT in scope (hard rule 7):** SonarQube Cloud in GitHub Actions with PR decoration.
Suggest it in the PR; do not build it. That also means the user needs no SONAR_TOKEN.

## Last test run
- none yet this phase. Baseline inherited from `main`: 190 Surefire + 30 Failsafe, smoke 125.

## Open issues / blockers
- **SonarQube is heavy** (~2 GB, needs Elasticsearch settings such as `vm.max_map_count`). It goes
  in its OWN `compose.sonar.yaml` so `docker compose up` stays a two-container dev stack.
- **JaCoCo must cover BOTH suites.** Surefire and Failsafe are separate runs, so two exec files
  and a merged report — a single `prepare-agent` would silently report unit coverage only.
- **"Fix the reported issues" is unbounded.** Plan: fix what Sonar flags in our own code; record
  anything deliberately not fixed, with the reason, rather than suppressing it silently.

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet.

## Environment left behind
Docker Desktop is RUNNING (it had stopped; restarted with `open -a Docker`). The compose stack is
up and healthy (`ecomdemo-app`, `ecomdemo-db`), schema v6. The pre-compose container
`ecomdemo-postgres` is stopped, not deleted. `.env` exists locally with a real JWT_SECRET and is
gitignored. No stray Java processes.

## Still outstanding (user, not blocking)
- **`required_status_checks` on `main` is `null`.** CI reports but does not block; PRs #11 and #12
  were both merged with a red run. Phase 11's stated manual step, still undone.

## Next action
Start step 5 (IMPLEMENTING) of the execution protocol on `feature/phase-12-sonarqube`: read
`docs/phases/phase-12-sonarqube.md`, then work the checklist above top-down with small
Conventional Commits, beginning with JaCoCo in `pom.xml` (both suites, merged report) and
`compose.sonar.yaml`.
