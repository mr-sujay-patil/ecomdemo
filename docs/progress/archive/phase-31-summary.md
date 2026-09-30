## Phase 31: Security Scanning (tag: phase-31-complete, PR #48)
**What exists now:** CI jobs `dependency-scan` (OWASP Dependency-Check 13.0.0, NVD, CVSS >= 7 fails,
test scope skipped) and `image-scan` (all 8 images built in one job, Trivy 0.74.0 by digest,
HIGH/CRITICAL fail, CycloneDX SBOM artifact per image); `publish` needs both. `docs/security.md` =
scan findings + OWASP API Top 10 (2023) review. catalog/inventory now enforce roles themselves.
**Key code:** root pom: `dependency-check-maven` in pluginManagement (run explicitly:
`NVD_API_KEY=... ./mvnw org.owasp:dependency-check-maven:aggregate`), security version overrides
`tomcat.version` 11.0.25, `jackson-bom.version` 3.1.6, `jackson-2-bom.version` 2.21.6 (REMOVE when Boot
manages >= these). `dependency-check-suppressions.xml` (2 false positives, until 2027-03-31),
`.trivyignore.yaml` (empty). catalog `SecurityConfig`: GET any token, writes + `/embeddings/**`
ADMIN|SERVICE; inventory: everything ADMIN|SERVICE (`ServiceTokens.ROLE`).
**Config & infrastructure:** GitHub secret `NVD_API_KEY` (set from the user's `.env`); NVD data cached
in CI (`~/.cache/dependency-check`, first download ~26 min in CI, ~40 min locally); locally the key
is read from `.env` (never print it). `trivy-reports/` gitignored.
**Tests:** +4: `InventorySecurityTest` (CUSTOMER 403 on read/write/reserve, ADMIN ok), `ProductApiIT`
(CUSTOMER reads but 403 on create/delete/backfill; ADMIN writes). CI blocking proven on PR #48 with
a temporary commons-text 1.9 commit, then reverted.
**Gotchas:** Dependency-Check 13 will not run without an NVD key. CPE matching is product-wide:
Kotlin build-tool CVEs hit kotlin-stdlib; pgvector extension CVEs hit the Java client. Dependency-Check
groups related jars (kotlin-reflect under kotlin-stdlib). Trivy writes root-owned files through the
docker socket mount (delete via a container).
**Follow-ups (not done):** login throttling per username; asymmetric JWT + scoped service identities;
bind compose ports to 127.0.0.1; gateway `/actuator/prometheus` not public; pagination on
`GET /api/products`; Trivy config/IaC scanning of Dockerfile and k8s manifests; Dependabot/Renovate.
