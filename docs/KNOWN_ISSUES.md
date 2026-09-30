# 🐞 Known Issues

The one list of known defects, gaps, and deferred work. Before this file, they were scattered across
the README's "Known gaps" sections, `docs/architecture/saga.md`, `docs/performance.md`,
`docs/security.md`, `RECENT.md` follow-ups, and code comments. That is how KI-001 went unnoticed from
Phase 21 until Phase 32.

**The rule:** a defect (something that used to work, or should work, and doesn't) is fixed on a
`fix/ki-XXX-<slug>` branch. A new capability or technology is a phase. The lifecycle is in
`docs/process/execution-protocol.md` (section 8).

## Triage values

| Triage | Meaning |
|---|---|
| **Fix** | A defect. Goes through the fix track, one branch and one PR per issue |
| **Phase N** | Covered by an approved phase. Closed by that phase's PR |
| **Candidate** | A new capability. Becomes a phase only if the user approves one |
| **Accepted** | A deliberate limit of a learning project, with the reason written down. No work planned |
| **Needs check** | Recorded as a gap at the time. Not re-verified against the current code; check before triaging |

**Status:** Open · In progress (branch) · Fixed (PR #, tag `ki-XXX-fixed`) · Won't fix (reason)

**Severity:** High (security hole, data loss, or wrong business outcome) · Medium (broken or missing
behaviour with a workaround, or a real risk under a plausible condition) · Low (cleanup, docs, cosmetics)

Adding an issue: take the next free ID, add a row, and add a detail section only when the row can't
carry the scope. Found during a phase or fix? Record it here in that branch; don't fix it in passing.

## Fix: the defect queue (in suggested order)

| ID | Issue | Severity | Since | Source | Status |
|---|---|---|---|---|---|
| KI-001 | Swagger UI and OpenAPI docs are unreachable since the monolith split (details below) | Medium | Phase 21 | `OpenApiDocumentationTest.java:163`, `security.md` API9 | In progress (`fix/ki-001-openapi-docs`) |
| KI-002 | Two outbox relays double-publish. catalog-service runs 2-4 pods in k8s (`k8s/hpa.yaml`, `minReplicas: 2`), and the relay takes no lock (`SELECT ... FOR UPDATE SKIP LOCKED` missing). Consumers may absorb it by `event_id`; confirm each one does | Medium | Phase 18, exposed by Phase 25 | `OutboxRelay.java:40` | Open |
| KI-003 | compose publishes every port on all interfaces: 6 PostgreSQL databases (default passwords), Redis and Kafka (no auth) | Medium | Phase 10 | `security.md` API8 | Open |
| KI-004 | No timeouts or circuit breaker on the app's inventory calls or on the gateway's `/api/products` route | Medium | Phase 22 | `security.md` API4, API10 | Open |
| KI-005 | No load shedding at checkout: under overload, requests wait 10 s for a connection instead of failing fast with 503 + `Retry-After` | Medium | Phase 30 | `security.md` API4, `performance.md` | Open |
| KI-006 | The gateway's `/actuator/prometheus` is public, and reveals route names, error rates and JVM details | Low | Phase 21 | `security.md` API8 | Open |
| KI-007 | `GET /api/products` returns the whole catalogue, unpaginated | Low | Phase 1 | `security.md` API4 | Open |
| KI-008 | Nothing prunes `processed_event`, which grows forever (its index already exists) | Low | Phase 17 | `architecture/saga.md` | Open |
| KI-009 | The README's "Known gaps (closed by later phases)" sections are stale: some items are closed, some still open, and the heading says all are closed. Replace them with a link to this file | Low | n/a | `README.md:3989` | Open |
| KI-010 | notification-service keeps its own copy of the idempotent-consumer code instead of using `ProcessedEvents` from the library | Low | Phase 24 | `ProcessedEvents.java:21`, `saga.md` | Open |
| KI-011 | Dead code: `InventoryGateway.reserve`/`release` and inventory's matching HTTP endpoints are no longer called by checkout | Low | Phase 24 | `architecture/saga.md` | Open |
| KI-039 | compose's Kafka keeps nothing across `down`/`up`: the `kafka-data` volume is mounted at `/var/lib/kafka/data`, but the broker writes to `/tmp/kafka-logs`. Every topic, offset and consumer group is lost while the PostgreSQL volumes survive | Medium | Phase 17 | Phase 32 merge verification (2026-09-30): `kafka-log-dirs.sh` reports `/tmp/kafka-logs` | Open |
| KI-040 | A dead letter is identified only by its Kafka address. `dead_letter_replay` is unique on `(dlt_topic, dlt_partition, dlt_offset)`, so once a DLT's offsets restart (topic recreated, or KI-039), a new dead letter at a reused offset is refused 409 "already replayed" and can never be replayed. Fails safe (never replays twice). Key it on the record's identity (e.g. the event ID) as well | Medium | Phase 32 | Phase 32 merge verification (2026-09-30): smoke with kept volumes 429/2 failed | Open |

### KI-001: Swagger UI and OpenAPI docs unreachable since the split

**Found:** 2026-09-29, by the user. **Branch:** `fix/ki-001-openapi-docs`.

**What's broken:** springdoc and `OpenApiConfig` reach every service through `common`, and every
servlet service builds a spec. But:
- Each service's `SecurityConfig` blocks `/v3/api-docs` and `/swagger-ui/**`. They fall through to
  `authenticated()`, `hasAnyRole(...)` or `denyAll()`. Only `ecomdemo-app` (host port 8084) opens them.
- The gateway routes only `/api/**`, so `http://localhost:8080/swagger-ui.html`, the URL the README
  gives, returns 404.
- All six specs are titled "EcomDemo API" with server `localhost:8080`, whichever service serves them.
- Only `ecomdemo-app` has springdoc properties and a spec test. `OpenApiDocumentationTest` asserts
  that the catalogue schema is absent, and its comment ("only ecomdemo-app declares springdoc") is
  no longer true.

**Fix scope:**
- Each service with an HTTP API (catalog, customer, inventory, assistant, app) publishes its own
  `/v3/api-docs`: open the docs paths in its `SecurityConfig`, give it its own title (from
  configuration, through `OpenApiConfig`), and use the gateway as the only server. Payment and
  notification keep their docs closed or disabled.
- The gateway routes `/v3/api-docs/{service}` to each service and serves one Swagger UI (springdoc's
  WebFlux UI) with a dropdown of the service specs. "Try it out" goes through the gateway.
- A spec test per documented service. The catalogue-schema test moves to catalog-service and
  asserts `ProductWrite` and its constraints are documented. Delete the stale comment.
- README (Swagger URL and per-service docs), `security.md` API9, `decisions.md`.

**Done when:** the gateway's Swagger UI lists every documented service, each spec loads, and an
authorized "Try it out" call succeeds through the gateway. Smoke additions: the gateway's Swagger UI
answers 200, `/v3/api-docs/{service}` returns an OpenAPI 3 document per documented service, the
catalog spec contains `ProductWrite`, and payment and notification expose no docs through the gateway.

## Covered by an approved phase

| ID | Issue | Phase | Status |
|---|---|---|---|
| KI-012 | An order whose saga event is dead-lettered stays PENDING forever | Phase 32 | Fixed in Phase 32 (saga deadline) |
| KI-013 | No reconciliation of `stock_reservation` against orders | Phase 32 | Fixed in Phase 32 (close + fence) |
| KI-014 | No login throttling or lockout (BCrypt cost is the only brake) | Phase 33 | Open |
| KI-015 | Symmetric JWT signing (HS256): every verifier can also mint tokens; one shared SERVICE role | Phase 33 | Open |

## Candidates: new capabilities (a phase only if approved)

| ID | Capability | Source |
|---|---|---|
| KI-016 | Alertmanager: alerts fire in Prometheus and are delivered nowhere | README "Known gaps" |
| KI-017 | Refresh tokens, token revocation, "log out everywhere" | README "Known gaps" |
| KI-018 | Password change through the API | README "Known gaps" |
| KI-019 | Restore the cart when an order is cancelled | `architecture/saga.md` |
| KI-020 | Hybrid (keyword + semantic) search | README (Phase 28) |
| KI-021 | A per-user budget for AI calls | `security.md` API4 |
| KI-022 | Performance: stock check outside the checkout transaction, pipelined outbox sends, shorter poll delay, push order status (SSE), gateway cost per request and replicas, a soak test, a separate load machine | `performance.md` |
| KI-023 | Trivy config/IaC scanning of the Dockerfile and k8s manifests; Dependabot for Docker base images and compose images (Maven and GitHub Actions are already covered, `.github/dependabot.yml`) | `RECENT.md` (Phase 31) |
| KI-024 | Tempo retention and object storage; span metrics (traces to metrics) | README "Known gaps" |

## Accepted limits

| ID | Limit | Why it's accepted |
|---|---|---|
| KI-025 | A 403 on someone else's order admits that it exists | A conscious judgement for orders; see `OrderService.findById` |
| KI-026 | No API versioning (`/api/v1`) | One client, one version |
| KI-027 | Swagger UI has no production profile to switch it off | No production deployment; revisit with one |
| KI-028 | Most of the fast suite runs on H2 | The PostgreSQL-specific paths are covered by the integration tests |
| KI-029 | The system issues plain JWTs; no OAuth2 or OIDC flows | Out of scope for a learning project |
| KI-030 | Flyway has no undo | Community edition; roll forward with a new migration |
| KI-037 | An order whose **StockRejected** is dead-lettered is cancelled by the saga deadline with the void's reason, not the stock message | The rejection is recorded nowhere the reconciler can ask; the outcome (CANCELLED, nothing held) is right, only the wording differs (Phase 32) |
| KI-038 | The saga deadline sweep runs in every app instance, making duplicate settle/close calls | Safe: both calls are idempotent and the decision is a conditional UPDATE; the app runs one replica (Phase 32) |

## Needs check (not re-verified against the current code)

| ID | Recorded gap | Source |
|---|---|---|
| KI-031 | Nothing watches the outbox (no pending-count gauge or health indicator). `OutboxObservationConfig` may have closed this | README "Known gaps" |
| KI-032 | Cache hit rate is published but not graphed (no dashboard uses `cache_gets_total`) | README "Known gaps" |
| KI-033 | The nightly `@Scheduled` report fires in every app instance (the app runs 1 replica today) | README "Known gaps" |
| KI-034 | Restarting a batch import after the container is replaced is inferred, not tested | README "Known gaps" |
| KI-035 | The gateway writes no request log; that it logs no bodies is an absence, not a decision | `RequestLogFilterTest.java:98` |
| KI-036 | No metrics cardinality budget is asserted anywhere | README "Known gaps" |
