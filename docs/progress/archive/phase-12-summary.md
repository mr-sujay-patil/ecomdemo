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
