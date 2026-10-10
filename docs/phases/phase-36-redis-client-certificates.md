# Phase 36: Redis Client Certificates

| | |
|---|---|
| **Stage** | Stage 9: Production Hardening |
| **Technology** | Mutual TLS (client certificates) for Redis, with Spring Boot SSL bundles on Lettuce |
| **Branch** | `feature/phase-36-redis-client-certificates` |
| **PR title** | `Phase 36: Redis Client Certificates` |
| **Requires** | `phase-35-complete`: Phase 35 is merged on `main` (`bf0c109`, PR #89); the tag is pushed by the owner (this session's tag pushes are refused) |
| **Completion tag** | `phase-36-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for Git. Approved by the user on 2026-10-10: **Redis client certificates plus the existing password** (KI-063).

**Technology:** Client authentication by certificate on the last data store that still trusted any client with the password

**Goal:** In Kubernetes, Redis knows that a connection comes from one of our services, not just that the line is encrypted (KI-057). Redis accepts a client only with a certificate from the cluster CA AND the password (`requirepass`, KI-050). Compose stays as it is.

**What you'll implement**
- **Redis:** `--tls-auth-clients yes`, verifying client certificates against the cluster CA (`--tls-ca-cert-file`); `requirepass` stays.
- **Client certificates:** the four Redis clients (app, catalog-service, gateway-service, assistant-service) present a certificate from the `ecomdemo-ca` ClusterIssuer. app and catalog-service already have one (`<service>-client-tls`, Phase 35); gateway-service and assistant-service get one. Decide and record whether a service reuses its Phase 35 certificate or gets a Redis-only one.
- **Clients:** present the certificate through a Spring Boot SSL bundle (`spring.data.redis.ssl.bundle`), with the CA as its truststore and `reload-on-update`.
- **Renewal:** client certificates renew every 60 days. Find out whether Lettuce uses a renewed certificate on its next connection with Boot's bundle reload alone, or needs the Phase 35 approach (a view of the registry's current bundle); prove the answer in a test. No silent expiry trap.
- **The tools inside the Redis pod:** the readiness probe, the `cert-reload` sidecar and the smoke test's `redis-cli` calls need a client certificate too (or a documented alternative). Nothing in `scripts/` or `k8s/` may still talk to Redis without one.
- **Per-service Redis ACL users** mapped from the certificate: out of scope unless the pinned image supports it trivially; otherwise a candidate row in `docs/KNOWN_ISSUES.md`.
- **Tests:** a fast manifest test that fails before the change; a Testcontainers IT on the real Redis image with the manifest's own command: certificate + password → OK; no certificate → refused; a certificate from another CA → refused; a certificate without the password → refused; a renewed client certificate is used by the next connection.
- **Docs:** README, `docs/decisions.md`, `docs/security.md`, KI-063.

**Concepts to understand**
- What `--tls-auth-clients yes` adds to TLS: the server now checks the client's certificate chain in the handshake, before any command
- Two factors on one connection: something the pod has (the private key) and something it knows (the password)
- Why a library that reads its key once needs help on renewal, and why a long-lived connection hides the problem until it reconnects
- Certificate usages (`server auth`, `client auth`) and why a server's own certificate can or cannot be used by its tools as a client

**Done when**
- Redis refuses a TLS client with no certificate, with a certificate from another CA, and with a valid certificate but no password, and accepts a service's certificate with the password; a renewed client certificate is used by the next connection without a restart. Each is proven by an automated test, and the k8s smoke test checks the refusals and every service's certificate on the cluster.

## Smoke test additions (`scripts/smoke-test.sh`, k8s only)

- Redis: a TLS client without a certificate is refused; one with a certificate from another CA is refused; each Redis client's certificate (app, catalog-service, gateway-service, assistant-service) gets PONG with the password.
- cert-manager: 25 certificates Ready (23 + the gateway's and the assistant's client certificates).

## Your manual steps (user)

Run `scripts/k8s-up.sh`, then `kubectl -n ecomdemo rollout restart deploy` (Redis, gateway-service and assistant-service roll out by themselves because their pod templates change, but app and catalog-service change only in their ConfigMaps, which does not restart a pod: until they restart they reach Redis without a certificate and are refused), then `scripts/k8s-smoke.sh`. The session that builds this phase cannot create a kind cluster, so the k8s smoke test is yours to run.
