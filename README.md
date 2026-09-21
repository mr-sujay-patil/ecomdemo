# EcomDemo

A learning project: an e-commerce application that evolves from a simple Spring Boot monolith into a
production-grade distributed system, **one technology per phase**. Each phase introduces exactly one
new technology, on its own feature branch, merged into `main` through a reviewed Pull Request.

**Stack:** Java 21 · Spring Boot 4.x · Maven Wrapper · Git + GitHub

## Current status

**Phase 0: Repository Bootstrap** — the repository, its Git rules and the documentation package are
in place. No application code yet; the Spring Boot baseline arrives in Phase 1.

## Roadmap

The full 32-phase plan, with a progress tracker, lives in **[docs/ROADMAP.md](docs/ROADMAP.md)**.

## Repository layout

```
ecomdemo/
├── CLAUDE.md     # rules and pointers for Claude Code
├── docs/         # roadmap, phase specs, process docs, decisions, progress
├── scripts/      # smoke test and helper scripts (from Phase 1)
└── .github/      # Pull Request template (workflows from Phase 11)
```

## How to work on this project

Every phase follows the same cycle, documented in
[docs/process/git-workflow.md](docs/process/git-workflow.md):

1. Cut `feature/phase-XX-<slug>` from the latest `main`.
2. Implement only that phase, in small Conventional Commits.
3. Run the full [testing protocol](docs/process/testing-protocol.md).
4. Raise a Pull Request into `main` and review it.
5. Merge with **"Create a merge commit"**, then tag `phase-XX-complete`.

Branches are never deleted — they are the permanent history of the learning journey.

## Building

From Phase 1 onwards:

```bash
./mvnw clean verify      # build and run all tests
scripts/smoke-test.sh    # end-to-end smoke test against a running app
```
