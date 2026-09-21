# Phase 03 Test Report: API Documentation

- **Date:** 2026-09-21
- **Branch:** `feature/phase-03-openapi`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1,
  springdoc-openapi 3.1.1
- **Result:** ✅ all checks passed (two springdoc advisory WARNs at startup — see §5)

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 94, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 7.523 s
```

Nothing is `@Disabled` or skipped. All 79 tests from Phases 1–2 still pass untouched; the 15 new
ones are the spec guard.

| Test class | Type | Tests | What it proves |
|---|---|---|---|
| `OpenApiDocumentationTest` | `@SpringBootTest` + `MockMvcTester` | 15 | The document metadata (`openapi` 3.x, title, version, non-blank description); that each of the seven `/api` paths is documented (one parameterised test per path) **and** that nothing else is — the H2 console and the Swagger UI resources stay out of the contract; every operation has a summary and a tag; every response has a description; every 4xx response is `application/json` with `$ref` → `ApiError`; `ApiError` has exactly `status` and `message`; every property of the three request schemas carries an example; and `ProductRequest` carries the `required`, `maxLength` and `minimum` that springdoc derived from the Bean Validation annotations |

Test count by layer: **35 unit · 33 web slice · 10 persistence slice · 16 full context**
(15 new + Phase 1's `PlaceOrderFlowTest`).

Why `@SpringBootTest` and not `@WebMvcTest`: springdoc contributes `/v3/api-docs` through
auto-configuration, which a web slice does not load, so the slice would 404. The class uses
JUnit's `PER_CLASS` lifecycle so a non-static `@BeforeAll` can use the injected `MockMvcTester`
and build the document once (199 ms) instead of once per test.

## 2. Application starts — `./mvnw spring-boot:run`

```
Tomcat started on port 8080 (http) with context path '/'
Started EcomdemoApplication in 2.226 seconds (process running for 2.379)
```

`grep -E "ERROR|WARN"` over the startup log → **0 ERROR, 2 WARN**, both from springdoc itself and
both expected — see §5.

## 3. End-to-end smoke test — `scripts/smoke-test.sh`

```
Summary: 48 passed, 0 failed
SMOKE TEST PASSED
```

Exit code 0. The 34 checks from Phases 1–2 are unchanged; 14 were added, per the phase file's
"Smoke test additions":

| New check | Result |
|---|---|
| `GET /v3/api-docs` returns 200 | PASS |
| the spec is titled `EcomDemo API` | PASS |
| the spec documents each of the 7 `/api` paths (7 checks) | PASS |
| every operation has a summary | PASS |
| every error response uses the `ApiError` schema | PASS |
| `GET /swagger-ui.html` returns 200 or a redirect (got 302 → `/swagger-ui/index.html`) | PASS |
| the Swagger UI page itself returns 200 | PASS |
| the Swagger UI javascript bundle is served | PASS |

The path list is written out in the script rather than read back from the spec: comparing the
spec to itself would pass even if an endpoint were missing from it.

## 4. "Done when" items

| Item | Status | How it was verified |
|---|---|---|
| Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs` | ✅ | Smoke checks above: 302 → `/swagger-ui/index.html` (200), spec 200. `swagger-ui-bundle.js` (1.5 MB), `swagger-ui.css` (186 KB) and `swagger-initializer.js` all serve 200 |
| Every endpoint is **documented** — 12 operations over 7 paths | ✅ | `OpenApiDocumentationTest` asserts exactly the 7 `/api` paths are present, each operation has a summary and a tag, and every response has a description |
| …with descriptions and examples | ✅ | Every property of `ProductRequest`, `AddCartItemRequest` and `UpdateCartItemRequest` carries an `example`, asserted in the test; the response schemas carry them too |
| Documented error responses | ✅ | All twelve 4xx responses are `application/json` with `$ref` → `ApiError`, asserted in both the test and the smoke script |
| Every endpoint is **callable** from Swagger UI | ⚠️ | Verified as far as this session can: the UI page, its javascript bundle and its `swagger-config` (`tryItOutEnabled: true`) all serve, and the spec they render is asserted complete. Chrome's site permissions block `localhost` for browser automation, so the rendered page was not clicked through from here. **Manual step for you:** `./mvnw spring-boot:run`, open <http://localhost:8080/swagger-ui.html>, expand any operation, press *Try it out* → *Execute*, and confirm the response comes back |

## 5. Notes and deviations

- **Two WARNs at startup, both springdoc's own:**
  `SpringDoc /v3/api-docs endpoint is enabled by default. To disable it in production, set the
  property 'springdoc.api-docs.enabled=false'` and the same for `/swagger-ui.html`. They are
  advisory: springdoc points out that an open spec endpoint tells an attacker the shape of the
  whole API. Left enabled deliberately — an explorable API is the point of this phase, and there
  is no production profile to switch it off in yet. Phase 8 (Spring Security) is where access to
  these two paths gets decided.
- **`@Content` needs an explicit media type.** The first version of the annotations used
  `@Content(schema = @Schema(implementation = ApiError.class))`, which documents the error bodies
  as `*/*` even though `GlobalExceptionHandler` returns `application/json`. The new smoke check
  caught it before the PR; every `@Content` now says `mediaType = "application/json"`.
- **Bean Validation is not restated.** `@Schema` carries only descriptions and examples.
  springdoc reads the existing `@NotBlank`/`@NotNull`/`@Size`/`@DecimalMin` annotations and emits
  `required`, `maxLength` and `minimum` itself, so duplicating them would create two sources of
  truth that could disagree.
- **No production code behaviour changed this phase.** The only non-annotation change is the new
  `OpenApiConfig` bean; no controller, service, repository or entity logic was touched.
