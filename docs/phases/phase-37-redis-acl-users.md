# Phase 37: Redis ACL Users per Service

| | |
|---|---|
| **Stage** | Stage 9: Production Hardening |
| **Technology** | Redis ACL users (least privilege per service), logged in from the client certificate (`tls-auth-clients-user CN`) |
| **Branch** | `feature/phase-37-redis-acl-users` |
| **PR title** | `Phase 37: Redis ACL Users` |
| **Requires** | `phase-36-complete`: Phase 36 is merged on `main` (PR #91) and merge-verified; the tag is pushed by the owner (this session's tag pushes are refused). Redis 8.10 (`redis:8-alpine`, the pinned image) has `tls-auth-clients-user` |
| **Completion tag** | `phase-37-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for Git. Approved by the user on 2026-10-11: **"KI-065 phase"**.

**Technology:** Authorization on the last data store where every client could do everything

**Goal:** In Kubernetes, Redis knows WHICH service a connection is and lets it touch only what its code uses. Since Phase 36 each Redis client presents its own certificate (CN `ecomdemo`, `catalog`, `gateway`, `assistant`), but all of them log in as the `default` user with one shared password, so the gateway may flush the catalogue cache and catalog-service may read the assistant's conversations (KI-065). Now Redis logs each connection in as the ACL user named by its certificate, and each user may run only the commands, on only the keys, its service needs. Compose stays as it is.

**What you'll implement**
- **The ACL:** one ACL user per Redis client, derived from the code: the catalogue cache's names (`product::*`, `productList::*`, and how `RedisCacheManager` clears a cache), the gateway's rate limiter (`request_rate_limiter.*` keys in database 1, run as a Lua script: `EVALSHA`/`EVAL` and the commands inside the script), the assistant's keys (`assistant:memory:*`, `assistant:action:*`, its `MULTI`/`EXEC` and `SCAN`), app's (no keys at all), plus what every Lettuce connection and the health indicator send. Explicit commands rather than whole categories where a category would grant more than the code uses; record each choice. No pub/sub channels.
- **The `default` user:** disabled, so a connection whose certificate names no Redis user can do nothing.
- **Identity:** Redis maps the certificate's CN to the user (`--tls-auth-clients-user CN`). Decide and record the password factor: per-user passwords, or certificate-only users (and what stops one service's certificate from logging in as another user with `AUTH`).
- **The clients:** stop sending the shared password (an `AUTH` would switch the connection back to `default`), in the ConfigMaps/Secrets and in `scripts/k8s-up.sh`, including on a cluster created before this phase.
- **Ops access:** the tools in the Redis pod (readiness probe, `cert-reload` sidecar, the smoke test's `redis-cli`) get a dedicated user named by the pod's certificate; record its rights and why.
- **Where the ACL lives:** one file in Git (a ConfigMap mounted into the Redis pod, `--aclfile`); decide how a change reaches a running Redis.
- **Tests (red first):** a fast manifest test (every key prefix the code uses is covered by exactly its own service's user; `default` is off; no client sends a password); a Testcontainers IT on the real image with the manifest's own arguments and ACL file: the catalog certificate logs in as `catalog`, uses its cache through Spring's `RedisCacheManager` and is refused another service's keys and `FLUSHALL`; the assistant's real operations work as `assistant`; app's certificate can PING and report health but touches no key; a cluster-CA certificate whose CN names no user is refused; no certificate is refused; the old shared password no longer logs in; the in-pod tools still work. A gateway IT proves the real rate limiter still limits (429) under its ACL user, and Redis's `ACL LOG` shows no refused command (the limiter fails open, so a missing permission would otherwise be silent).
- **Docs:** README, `docs/decisions.md`, `docs/security.md`, KI-065.

**Concepts to understand**
- Authentication vs authorization in Redis: `--tls-auth-clients` proves the certificate, the ACL user decides what it may do
- Least privilege: key patterns (`~prefix*`), command rules (`+get`, `-@dangerous`), and why a category can grant far more than its name suggests
- Why a certificate-only user has no password at all, and what an `AUTH` does to a connection that is already logged in
- Fail-open code (the rate limiter, the cache error handler) and why a permission test must look at the server's refusal log, not only at the client

**Done when**
- Each service's certificate logs Redis in as its own user, which can do what the code does and is refused another service's keys and administrative commands; a certificate naming no user, no certificate, and the old shared password are refused; the rate limiter still returns 429. Each is proven by an automated test, and the k8s smoke test checks each service's certificate against its own and another service's keys on the cluster.

## Smoke test additions (`scripts/smoke-test.sh`, k8s only)

- Redis: each of the four client certificates gets PONG without a password, reads a key of its own service and is refused (`NOPERM`) a key of another service; catalog's certificate is refused `FLUSHALL`; a cluster-CA certificate whose CN is not a Redis user (customer-service's) gets `NOAUTH`.
- The Caching section's "Redis refuses a client that has no password" stays on compose; on the cluster the refusal of a client without a Redis identity is the check above.

## Your manual steps (user)

Run `scripts/k8s-up.sh` (it also removes the shared Redis password from the four services' Secrets), then `kubectl -n ecomdemo rollout restart deploy` (the four Redis clients change only in their Secrets, which does not restart a pod: until they restart they send the old password and are refused), then `scripts/k8s-smoke.sh`. The session that builds this phase cannot create a kind cluster, so the k8s smoke test is yours to run.
