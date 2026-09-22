# Recent Phase Summaries (rolling window: last 2 phases)

> Newest first. When a third summary is added, move the oldest to `docs/progress/archive/phase-XX-summary.md`. Maximum ~30 lines per summary: facts only, no narrative.

<!-- TEMPLATE
## Phase XX: <Title> (tag: phase-XX-complete, PR #N)
**What exists now:** <1–3 lines describing the system after this phase>
**Key code:** <packages and classes that matter next>
**Config & infrastructure:** <profiles, env vars, ports, containers, and how to run>
**Tests:** <new test classes, smoke test additions>
**Gotchas:** <anything surprising the next phase must know>
**Follow-ups (not done, out of scope):** <suggestions deferred to later phases>
-->

## Phase 12: Code Quality (tag: phase-12-complete, PR #14)
**What exists now:** Coverage is measured across BOTH suites and the project passes a quality
gate. JaCoCo runs two agents (Surefire and Failsafe fork separate JVMs) and merges the exec files
at `verify`: 96.1% overall, 97.4% line, 81.0% branch. SonarQube Community runs in its own compose
stack; the first analysis found 16 issues / 92 min debt / reliability D / security D, and the
project now reports **0 bugs, 0 vulnerabilities, 0 smells, 0 debt, A/A/A, QUALITY GATE OK**.
220 tests and the 125-check smoke test are unchanged. Schema still V6.
**Key code:** `pom.xml` — jacoco 0.8.15 with `prepare-agent` + `prepare-agent-integration` +
`merge` + `report` (declared AFTER failsafe so the verify-phase executions run in the right
order), Surefire/Failsafe argLines reading `@{jacocoUnitArgLine}` / `@{jacocoItArgLine}` with
empty defaults, `sonar.coverage.jacoco.xmlReportPaths` -> the MERGED xml, `sonar.coverage.exclusions`
for `EcomdemoApplication` and `**/dto/**`, sonar-maven-plugin 5.8.0.7211 pinned and unbound.
`compose.sonar.yaml` (sonarqube + its own postgres, three named volumes, a status-endpoint
healthcheck). `scripts/sonar-setup.sh` — the quality gate as code, idempotent.
**Config & infrastructure:** No application change. New: `compose.sonar.yaml`,
`scripts/sonar-setup.sh`. Quality gate "EcomDemo way" = Sonar's four defaults +
`new_reliability_rating` and `new_security_rating` at A; every condition on NEW code.
**Tests:** No test added or removed. Fifteen Sonar findings fixed in place — the real bug was
`new SecureRandom()` per call in `JwtConfig`; `OrderAuditService.record` -> `recordAttempt`;
`throws Exception` dropped from `securityFilterChain` (Spring Security 7 no longer declares it,
confirmed by compiling); `RestTemplateBuilder.rootUri` (deprecated for removal) -> a
`DefaultUriBuilderFactory`; five `assertThatThrownBy` lambdas narrowed to one throwing call; four
minor test smells. Test report: `docs/test-reports/phase-12.md`.
**Gotchas:** `sonarqube:lts-community` still resolves to 9.9 and dies mid-migration against
PostgreSQL 18 — pin an exact `*-community` build; the LTA tags are paid-tier only. A literal
`<argLine>` OVERRIDES JaCoCo's injected one, so coverage silently reads 0%; `@{...}` late
evaluation is the fix. A Sonar issue resolved as "Accepted" lives only in the server database and
comes back when the volume is wiped — `@SuppressWarnings("java:S4502")` puts the decision in Git.
Writing `scripts/sonar-setup.sh` surfaced three shell bugs worth remembering: SonarQube answers a
bad password with 401 and an EMPTY body; `x="$(fn)"` runs `fn` in a subshell so globals it sets
are discarded; and `curl` needs `-G --data-urlencode` for a GET parameter containing a space.
**Follow-ups (not done, out of scope):** SonarQube Cloud + PR decoration so the gate actually
blocks a merge — optional in the phase file, deliberately skipped, and the obvious next step.
Raising branch coverage (81%) rather than line coverage. Image scanning — Phase 31.

## Phase 11: Continuous Integration (tag: phase-11-complete, PRs #11 and #13)
**What exists now:** Every PR is built and tested by GitHub Actions before it can be merged, and
every push to `main` publishes an image to GHCR tagged `sha-<short>` and `latest`. Proven, not
assumed: a deliberately failing test was committed, the run went red with the publish job skipped
and the reports still uploaded, and the commit was reverted. Build ~73 s cold, ~57 s with the
Maven cache. No application code changed; 190 + 30 tests and the 125-check smoke test unchanged.
**Key code:** `.github/workflows/ci.yml` — one workflow, two jobs. `build` (checkout@v7,
setup-java@v6 with `cache: maven`, `./mvnw -B clean verify`, upload-artifact@v7 with
`if: always()`, a `$GITHUB_STEP_SUMMARY` table built from the Surefire/Failsafe XML).
`publish` (`needs: build`, `if: push && ref == refs/heads/main`, `packages: write` on that job
alone, login-action@v4 with the built-in `GITHUB_TOKEN`, metadata-action@v6 for the two tags,
build-push-action@v7 with `cache-from/to: type=gha,mode=max`). `.github/dependabot.yml` — weekly
Maven and github-actions, Spring modules grouped.
**Config & infrastructure:** No Maven dependencies, no application changes, schema still V6. The
only new infrastructure is the GHCR package, created by the first publish. `permissions:
contents: read` workflow-wide. `concurrency` cancels superseded PR runs but never `main` runs.
**Tests:** None added or changed — CI runs the existing suite. `scripts/smoke-test.sh` is
untouched this phase. Test report: `docs/test-reports/phase-11.md`.
**Gotchas:** GitHub's ubuntu runners have a Docker daemon, so Testcontainers works with no
`service:` container and no CI-only datasource — the run log shows `postgres:18-alpine` starting
in 1.4 s. `if: always()` on the upload step is what makes reports available from a RED build
(126 KB artifact captured from the failing run). A red check shows the PR as `UNSTABLE`, NOT
`BLOCKED`, until `required_status_checks` is added to the branch protection — it was `null` at the
time of writing, which is the user's manual step. `needs: build` is the only thing stopping a red
commit from publishing an image.
**Follow-ups (not done, out of scope):** running the smoke test against the compose stack in CI —
optional in the phase file, deliberately skipped, and the most obviously worthwhile next addition.
Image vulnerability scanning — Phase 31. Actual deployment — Phases 25-26. Pinning actions by
commit SHA rather than major version — not planned.
