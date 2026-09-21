# Phase 27: LLM Integration

| | |
|---|---|
| **Stage** | Stage 8: AI Features |
| **Technology** | Spring AI |
| **Branch** | `feature/phase-27-spring-ai` |
| **PR title** | `Phase 27: LLM Integration` |
| **Requires** | `phase-26-complete` tag exists on `main` |
| **Completion tag** | `phase-27-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Spring AI

**Goal:** Add generative AI features the enterprise way.

**What you'll implement**
- Spring AI (a version compatible with your Boot version) in catalog-service.
- An ADMIN endpoint generating product descriptions, with structured output mapped to a record (description, tags, SEO title).
- Prompt templates as resource files.
- A configurable provider (OpenAI, Anthropic, Azure OpenAI, or Ollama), with the key from the environment.
- Timeouts, error handling, and token metrics.

**Concepts to understand**
- Chat models and message roles
- Temperature
- Structured output
- Cost, latency, and rate limits
- Prompt injection

**Done when**
- Generated descriptions are saved, and failures degrade gracefully.

## Smoke test additions (`scripts/smoke-test.sh`)

The description generation endpoint returns structured JSON. Without an API key, report the check as ⚠️ with setup steps; never fake it.

## Your manual steps (user)

Provide an LLM API key as an environment variable, or install Ollama and pull a model for a free local option.
