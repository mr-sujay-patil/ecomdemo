# Phase 2: Automated Testing

| | |
|---|---|
| **Stage** | Stage 1: Foundation |
| **Technology** | JUnit 5 + Mockito + MockMvc |
| **Branch** | `feature/phase-02-testing` |
| **PR title** | `Phase 02: Automated Testing` |
| **Requires** | `phase-01-complete` tag exists on `main` |
| **Completion tag** | `phase-02-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** JUnit 5 + Mockito + Spring test slices (MockMvc)

**Goal:** Build a safety net for every future change.

**What you'll implement**
- Unit tests for every service class, with repositories mocked through Mockito.
- `@WebMvcTest` controller tests (status codes, JSON body, validation errors).
- `@DataJpaTest` tests for custom queries.
- Naming convention `methodName_condition_expectedResult`, with AssertJ assertions and a Given / When / Then structure.

**Concepts to understand**
- The test pyramid
- Mocks vs stubs vs spies
- What `@WebMvcTest`, `@DataJpaTest`, and `@SpringBootTest` each load

**Done when**
- Every service method has success and failure tests, and `./mvnw clean verify` passes.

**Not in this phase:** coverage reports (Phase 12), real-database tests (Phase 7).

## Smoke test additions (`scripts/smoke-test.sh`)

No new checks. The existing script must still pass.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.
