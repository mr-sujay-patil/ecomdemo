# 🧪 Testing & Acceptance Protocol

Applies to every phase and every fix. A fix runs steps 1, 3, 4, 7 and 8 in full, adds the **regression test** that reproduced the defect (it must fail without the fix), and reports results in its PR description instead of a `docs/test-reports/` file.

A phase is **not done** until all of this passes on the feature branch before the PR is raised, and the regression and smoke test pass again on `main` after the merge.

> **Refined by the workflow rules in `CLAUDE.md` ("Workflow rules to reduce cycle time"), which win where they differ:**
> - Step 1 while iterating: build and test only the affected module (`./mvnw -pl <module> -am verify`); run the full `./mvnw -B verify` once, just before opening the PR. A change to the API used by `performance-tests` also needs `./mvnw -B -f performance-tests/pom.xml test-compile`.
> - Steps 3 and 4 (run the application and the smoke test, on compose or the kind cluster): only when the change touches deployment manifests, configuration, startup, or communication between services. Otherwise skip them and say why in the PR description.
> - When a smoke-test check fails, rebuild and roll out only the affected service and re-run the failing check; run the full smoke test once at the end. Rebuild and `kind load` only the images whose code changed.
> - "After the merge" no longer means a second local run on `main`: see section 5 of `execution-protocol.md`.
> - A PR is offered for approval only when its CI run is green. Commands expected to take more than a few minutes run in the background.

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

