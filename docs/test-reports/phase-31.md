# Phase 31 Test Report: Security Scanning (OWASP Dependency-Check + Trivy)

- **Date:** 2026-09-29
- **Branch:** `feature/phase-31-security-scanning`
- **Machine:** the WSL2 workstation. Claude Code produced every local result below on that machine.
  CI results are GitHub Actions runs on PR #48.
- **Toolchain:** Java 21.0.12, Maven 3.9.16, Docker 29.8.1, Spring Boot 4.1.1 (Tomcat and Jackson
  overridden by one patch each), OWASP Dependency-Check 13.0.0, Trivy 0.74.0.
- **Result:** ✅ green.
  - `./mvnw clean verify`: BUILD SUCCESS, **620 tests** (482 unit, 138 integration), 0 failed,
    0 skipped.
  - Done-when, **"CI blocks vulnerable builds"**: proven on PR #48. A commit adding commons-text 1.9
    failed **both** scans; its revert passed (§2).
  - Done-when, **"the security document is complete"**: `docs/security.md`, with scan findings,
    suppression policy and all ten OWASP API Top 10 (2023) items.
  - Compose, cold (`down -v`, `--build`, `.smoke-state` removed), the user's `.env`: smoke
    **404 / 0 / 0**.
  - Kubernetes (kind, upgraded in place, all 8 Deployments restarted): smoke **360 / 0 / 7**, the
    same as Phases 29 and 30.

## 1. Full regression

`./mvnw clean verify`: BUILD SUCCESS, 620 tests, up from 616. New:

| Test | Kind | What it proves |
|---|---|---|
| `InventorySecurityTest.refusesACustomer` | unit (MockMvc, real chain) | a CUSTOMER's valid token gets 403 on `GET`, `PUT /api/inventory/1` and `reserve` |
| `InventorySecurityTest.acceptsAnAdmin` | unit | an ADMIN may read and set a stock level |
| `ProductApiIT.aCustomerCannotWrite` | integration | a CUSTOMER reads (200) but gets 403 on create, delete and `/embeddings/**`; the product survives |
| `ProductApiIT.anAdminCanWrite` | integration | a relayed ADMIN token may create |

The existing tests (SERVICE-token writes, the gateway's `EdgeSecurityIT`, the smoke test's admin
flows) all still pass with the new rules.

## 2. CI blocks vulnerable builds

| Run | Head | Build and test | Dependency scan | Image scan | Publish |
|---|---|---|---|---|---|
| [36552629440](https://github.com/mr-sujay-patil/ecomdemo/actions/runs/36552629440) | `cd3f02a` (phase work) | ✅ | ✅ (26 min, first NVD download) | ✅ | skipped (PR) |
| [36555557687](https://github.com/mr-sujay-patil/ecomdemo/actions/runs/36555557687) | `9065a72` (+ commons-text 1.9) | ✅ | ❌ | ❌ | skipped |
| [36556218892](https://github.com/mr-sujay-patil/ecomdemo/actions/runs/36556218892) | `9484507` (reverted) | ✅ | ✅ (58 s, cached) | ✅ | skipped (PR) |

What the red run said:

    Dependency-Check: commons-text-1.9.jar (pkg:maven/org.apache.commons/commons-text@1.9, ...): CVE-2022-42889(9.8)
    Trivy:            org.apache.commons:commons-text (commons-text-1.9.jar) | CVE-2022-42889 | CRITICAL | fixed | 1.9 | 1.10.0

The tests still passed on that commit. Only the scans stopped it, which is the point: a
vulnerable dependency doesn't break anything a test can see. `publish` needs all three jobs, so on
`main` the image would not have been pushed either.

The demonstration is in the branch history on purpose: `9065a72` adds the dependency and
`386cacb` reverts it. ⚠️ `386cacb`'s message reads "placeholder". A mistaken `git commit --amend`
overwrote the revert's message before it was pushed. It was left as is rather than rewriting
history a second time, and `9484507`'s message records what it is. Its content was checked: the pom
is identical to before `9065a72`.

## 3. Scan findings and what was done

| Finding | Severity | Found by | Action |
|---|---|---|---|
| CVE-2026-65182, -65905, -68525 in Tomcat 11.0.24 (7 images) | CRITICAL | Trivy | fixed: `tomcat.version` 11.0.25 |
| CVE-2026-68497 in jackson-databind 3.1.5 (8 images) and 2.21.5 (7 images) | HIGH | Trivy | fixed: 3.1.6 and 2.21.6 |
| CVE-2026-53914 on kotlin-stdlib / -common / -reflect | CRITICAL | Dependency-Check | suppressed: false positive, the CVE is in Kotlin's build cache |
| CVE-2026-18022 on the pgvector Java client | HIGH | Dependency-Check | suppressed: false positive, the CVE is in the PostgreSQL extension; running extension 0.8.6 (fixed), HNSW index, 64-bit, checked with SQL |

After the fixes:

- Trivy: **8 of 8 images clean** at HIGH/CRITICAL (the exact CI steps were run locally, then in CI).
- Dependency-Check: **142 dependencies, 0 HIGH/CRITICAL** unsuppressed. 10 MEDIUM findings are
  listed in `docs/security.md`; they're below the gate.
- `verify` passed with the patched Tomcat and Jackson (the 620 above).

## 4. The OWASP API5 gap: found, fixed, re-probed

A CUSTOMER token sent straight to the service ports (compose publishes them):

| Request | Before (review) | After (rebuilt services; again on the cold stack) |
|---|---|---|
| `GET :8082/api/inventory/1` | 200 | 403 |
| `PUT :8082/api/inventory/1` | 200 (accepted) | 403 |
| `POST :8081/api/products` (empty body) | 400 (past authorization) | 403 |
| `DELETE :8081/api/products/999999` | 404 (past authorization) | 403 |
| `GET :8081/api/products` (a read) | 200 | 200 |
| `GET :8080/api/products` (anonymous, through the gateway) | 200 | 200 |

The review's "before" probes changed no data: the stock write re-set the existing level. The user
approved fixing this in the phase.

## 5. Compose

Cold: `docker compose down -v`, `.smoke-state` removed, `docker compose up -d --build`, every
container with a health check healthy. A copy of `scripts/smoke-test.sh`: **404 passed, 0 failed,
0 skipped**, with the user's `.env`. No new smoke checks, as the phase file specifies.

## 6. Kubernetes (kind)

`scripts/k8s-up.sh`, then `kubectl rollout restart` of all eight service Deployments (every image
changed: Tomcat and Jackson). All rolled out. `scripts/k8s-smoke.sh`: **360 / 0 / 7**, with the
same seven SKIPs as before (observability not in the cluster, AI providers `none`).

## 7. ⚠️ Needs you

1. **The NVD key.** It's in your `.env` and in the repository secret `NVD_API_KEY` (set with
   `gh secret set` from `.env`, never printed). If `dependency-scan` ever fails with an API-key
   error, request a new key and update both places.
2. **Suppressions expire on 2027-03-31.** On that date the two false positives fail the build again
   and need a fresh look. That's intended.
3. **Follow-ups** from the review, in `docs/security.md`:
   - login throttling per username
   - asymmetric JWT signing
   - binding compose ports to `127.0.0.1`
   - making the gateway's `/actuator/prometheus` non-public
   - pagination on `GET /api/products`
   - Trivy config scanning of the Dockerfile and k8s manifests
   - Dependabot or Renovate

## 8. Clean-up

- Trivy's root-owned output was removed through a container, and `trivy-reports/` is gitignored.
- The NVD database stays cached in `~/.cache/dependency-check`. That's intended; it makes the next
  local run take seconds.
- The compose stack (the user's `.env`) and the kind cluster are running on the Phase 31 build.
- The probe account `sec-probe` exists in the cold stack's customer database.
