# 🧪 Testing & Acceptance Protocol

Applies to every phase.

A phase is **not done** until all of this passes on the feature branch before the PR is raised, and the regression and smoke test pass again on `main` after the merge.

1. **Full regression.** `./mvnw clean verify` runs **all** unit and integration tests from **every** phase so far. Zero failures, and no test is skipped or `@Disabled` without a documented reason.
2. **Phase acceptance tests.** Every **Done when** item has an automated test or scripted check proving it.
3. **Run the complete application** the way it runs at this phase: `./mvnw spring-boot:run` early on, `docker compose up` from Phase 10, and the local Kubernetes cluster from Phase 25. The app must start with no errors in the logs.
4. **End-to-end smoke test.** `scripts/smoke-test.sh` (created in Phase 1) runs the full business flow against the running application:
   - list products → add to cart → view cart → place order → verify the order and the stock decrease
   - negative cases: unknown product (404), invalid input (400), insufficient stock (409)
   - the new checks listed under "Smoke test additions" in the current phase file

   **Every phase extends this script; checks are never removed.** It exits non-zero on any failure, which makes it a growing regression suite for the whole application.
5. **Failure-scenario checks** where the phase is about resilience or reliability. Examples: Kafka down (Phases 17–18), a downstream service down (Phase 22), payment failure (Phase 24), pod killed (Phase 25).
6. **Test report.** Commit `docs/test-reports/phase-XX.md` on the feature branch. It records the commands run, the results, key output excerpts, and any item needing manual verification, with steps for you. Summarize it in the PR.
7. **Clean up.** Stop the app and containers after testing, and leave no stray processes or files.
8. **Honesty rule.** Never report a check as passed without running it. If something cannot be verified automatically (for example, how a Grafana dashboard looks), mark it ⚠️ and give manual steps.

## Context-friendly testing

- Never paste full build or test logs into the conversation. Use `tail`, `grep`, or the Surefire/Failsafe summary lines.
- On failure, read only the relevant stack trace section and the failing test.
- Record results in the test report and `docs/progress/CURRENT.md`, not only in chat.

