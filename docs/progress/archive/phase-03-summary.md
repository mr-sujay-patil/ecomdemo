## Phase 03: API Documentation (tag: phase-03-complete, PR #3)
**What exists now:** The unchanged Phase 1 API now describes itself. springdoc-openapi builds an
OpenAPI 3 document from the code at startup, served at `/v3/api-docs` (and `.yaml`) and rendered
by Swagger UI at `/swagger-ui.html`. No production logic changed this phase.
**Key code:** `com.ecomdemo.common.OpenApiConfig` holds the document metadata as an `OpenAPI`
bean. Controllers carry `@Tag` (one per feature), `@Operation` and `@ApiResponse`; the DTO records
carry `@Schema` with descriptions and examples; every error response points at `ApiError`.
`OpenApiDocumentationTest` (`@SpringBootTest`, PER_CLASS lifecycle) asserts the document.
**Config & infrastructure:** `springdoc.api-docs.path`, `springdoc.swagger-ui.path`,
`tags-sorter`, `operations-sorter` and `try-it-out-enabled` in `application.properties`. One new
dependency, `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1`, pinned through a
`springdoc.version` property — 3.x is the Boot 4 line, 2.x is Boot 3. It drags Jackson 2 onto the
classpath beside Boot 4's Jackson 3; harmless, but `tools.jackson.databind` is the one to import.
**Tests:** 94 total, was 79 — `OpenApiDocumentationTest` adds 15 (8 plus one per API path). The
smoke test grows from 34 checks to 48.
**Gotchas:** springdoc logs two WARNs at startup saying the docs endpoints are enabled —
advisory, deliberate, and Phase 8 decides access. `@Content` without `mediaType` documents an
error body as `*/*`, so always write `mediaType = "application/json"`. An `@ApiResponse` for a 2xx
does **not** wipe the schema springdoc derives from the return type, as long as it carries no
`@Content` of its own. A `@WebMvcTest` slice cannot see `/v3/api-docs`: it comes from
auto-configuration, so the spec test has to be a `@SpringBootTest`.
**Follow-ups (not done, out of scope):** securing or disabling the docs endpoints — Phase 8.
Splitting the spec into groups per module — Phases 19–20. Generating a client from the YAML —
not planned.
