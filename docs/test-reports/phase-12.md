# Phase 12 Test Report: Code Quality

- **Date:** 2026-09-22
- **Branch:** `feature/phase-12-sonarqube`
- **Toolchain:** JaCoCo 0.8.15, sonar-maven-plugin 5.8.0.7211,
  SonarQube Community 26.9.0.129388 (`sonarqube:26.9.0.129388-community`) on
  `postgres:18-alpine`, JDK 21, Maven Wrapper 3.9.16, Docker 29.7.2, Compose v5.4.0
- **Result:** ✅ the quality gate passes, with **0 bugs, 0 vulnerabilities, 0 code smells and
  0 minutes of technical debt**. Nothing is deferred.

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 190, Failures: 0, Errors: 0, Skipped: 0     (surefire)
Tests run: 30,  Failures: 0, Errors: 0, Skipped: 0     (failsafe)
BUILD SUCCESS
```

Run after every change in this phase. No test was added, removed or disabled — the quality work
changed production code and test *style*, and the suite is what proved none of it changed
behaviour. Two of the fixes were caught by the compiler rather than by a test, which is noted in
§4.

## 2. Coverage — the number, and why it is the right one

```
INSTRUCTION   2246/2327   96.5%
BRANCH          34/42     81.0%
LINE           538/553    97.3%
METHOD         210/220    95.5%
CLASS           54/54    100.0%
```

That is the **merged** figure. JaCoCo runs two agents — Surefire and Failsafe fork separate JVMs —
and the two `.exec` files are merged at `verify`:

```
[INFO] --- jacoco:0.8.15:merge (merge-coverage) ---
Loading execution data file .../jacoco-it.exec
Loading execution data file .../jacoco-unit.exec
Writing merged execution data to .../jacoco-merged.exec
```

**Why that matters more than the number.** A single `prepare-agent` — the configuration in most
tutorials — instruments the unit run only. Here that would have quietly excluded the 30
integration tests, which are the only ones exercising the controllers over real HTTP. The report
would have been wrong in the direction that flatters, and nothing would have said so.

A second trap was already in the pom: Surefire and Failsafe both pinned an `<argLine>` for the
Mockito agent, and a literal `<argLine>` **overrides** the one JaCoCo injects. Left alone, coverage
would have read 0% with no error to explain it. Both now read it from a property with `@{...}`
late evaluation.

By package, lowest first:

| Line coverage | Package |
|---:|---|
| 33.3% (1/3) | `com.ecomdemo` — `EcomdemoApplication`, excluded from the Sonar figure |
| 93.5% | `com.ecomdemo.common` |
| 96.0% | `com.ecomdemo.order` |
| 96.9% | `com.ecomdemo.product` |
| 97.3% | `com.ecomdemo.cart` |
| 97.4% | `com.ecomdemo.auth` |
| 100% | `com.ecomdemo.security`, `com.ecomdemo.customer`, all `dto` packages |

## 3. What SonarQube found on the first run

16 issues, 92 minutes of estimated debt, reliability **D** and security **D**:

| Type | Severity | Rule | Where | What |
|---|---|---|---|---|
| Bug | Critical | `java:S2119` | `JwtConfig:68` | `new SecureRandom()` per call |
| Vulnerability | Critical | `java:S4502` | `SecurityConfig:79` | CSRF protection disabled |
| Smell | Major | `java:S5778` ×5 | 3 test classes | `assertThatThrownBy` lambda with two throwing calls |
| Smell | Major | `java:S5738` | `IntegrationTest:129` | deprecated **for removal** API |
| Smell | Major | `java:S112` | `SecurityConfig:61` | generic `throws Exception` |
| Smell | Major | `java:S6213` | `OrderAuditService:44` | method named `record` |
| Smell | Minor | `java:S1130` | `SecurityConfig:61` | declared exception cannot be thrown |
| Smell | Minor | `java:S5853` ×2 | 2 test classes | assertions not chained |
| Smell | Minor | `java:S5838` | `TokenServiceTest:62` | `.toString()` instead of `hasToString` |
| Smell | Minor | `java:S6068` | `OrderServiceTest:87` | redundant `eq(...)` |
| Smell | Minor | `java:S8924` | `ProductControllerTest:309` | fully-qualified `Mockito.doThrow` |

## 4. What was fixed, and what each one was actually worth

**The bug was a real bug.** `new SecureRandom()` on every call re-seeds from the OS entropy source
each time — slow, and on some platforms a burst of instances created in the same moment can be
seeded from correlated state. One static instance is also explicitly thread-safe and accumulates
entropy. It is reached once at startup here, but "it only happens once" is how the habit survives
into code where it happens constantly.

**Two findings were confirmed by the compiler, not taken on trust.**

- `throws Exception` on `securityFilterChain` is in every Spring Security example because
  `HttpSecurity.build()` used to declare it. Spring Security 7 does not. Rather than believe the
  analyser, it was removed and the project compiled — `BUILD SUCCESS`. Keeping it would declare a
  failure that cannot happen and force callers to handle it.
- `RestTemplateBuilder.rootUri(String)` is deprecated **for removal**, which `javac` had been
  saying all along:
  ```
  [WARNING] IntegrationTest.java:[129,17] [removal] rootUri(...) has been deprecated and marked for removal
  ```
  Replaced with the `DefaultUriBuilderFactory` that method was configuring anyway; the warning is
  gone.

**The five `java:S5778` fixes are the most interesting.** This:

```java
assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", "whatever-password")))
```

passes if **either** the constructor or `login()` throws. The test therefore does not pin down
which — a `LoginRequest` that later started validating its arguments would keep this green while
the behaviour under test rotted. Hoisting the argument out of the lambda makes the assertion mean
what it says. That is a test correctness issue wearing a code-smell label.

**`record` → `recordAttempt`.** `record` is a restricted identifier (it names the class kind from
Java 16). The rename is cheap now and more expensive with every future caller.

The four remaining minor smells were assertion chaining, `hasToString`, a redundant `eq(...)` and
a static import — style, fixed because they cost nothing.

## 5. The one that was not "fixed": CSRF

`java:S4502` is a **review** rule — "make sure disabling Spring Security's CSRF protection is safe
here" — not a defect report. The answer is the argument already in `SecurityConfig`, the README
and `docs/decisions.md` [Phase 08]: this API is stateless, sets no cookie, and reads a Bearer token
from a header the client must attach deliberately, so a cross-site form arrives with no
credentials and is answered 401. There is no ambient authority to forge.

It was first resolved as **Accepted** in SonarQube with a comment. Then the server was wiped to
test the setup script — and the issue came back, because that resolution lived only in SonarQube's
database. So it moved into the code:

```java
@SuppressWarnings("java:S4502")
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) {
```

with the reasoning in the Javadoc above it. The decision is now version-controlled, visible in
code review, and survives a rebuilt server — and deleting the annotation is what makes the rule
speak up again the day this application adopts cookies.

## 6. Final analysis

| Metric | Value |
|---|---|
| Lines of code | 2,325 |
| Coverage (merged) | **96.1%** (line 97.4%, branch 81.0%) |
| Bugs | **0** |
| Vulnerabilities | **0** |
| Code smells | **0** |
| Security hotspots | **0** |
| Duplicated lines | **0.0%** |
| Technical debt | **0 min** (was 92) |
| Reliability / Security / Maintainability | **A / A / A** (was D / D / A) |

## 7. "Done when": the project passes the quality gate

```
QUALITY GATE: OK
  OK  new_reliability_rating         actual=1     fails if GT 1
  OK  new_security_rating            actual=1     fails if GT 1
  OK  new_duplicated_lines_density   actual=0.0   fails if GT 3
  OK  new_violations                 actual=0     fails if GT 0
```

Run with `-Dsonar.qualitygate.wait=true`, so the **build** fails on a red gate rather than
printing a link to a dashboard nobody opens.

The gate is `EcomDemo way`: Sonar's four defaults plus `new_reliability_rating` and
`new_security_rating` at A. Every condition is on **new code** — a rule about the whole project
either passes on day one and teaches nothing, or fails on day one and gets switched off. Coverage
on new code stays at Sonar's 80 rather than the phase file's example of 70: the project sits at
97.4%, and lowering a threshold it already beats would be loosening the gate, not setting it.

An honest note on reading that output: `new_coverage` and `new_security_hotspots_reviewed` are
absent from the conditions above because the analysis found no new code in the period on a
freshly created project. They are configured and they evaluate — an earlier run on this same
project reported `new_coverage actual=100.0 OK`.

## 8. The gate is reproducible

A quality gate lives in SonarQube's database, which makes it the one piece of this setup not in
Git: `down -v` deletes it, and a colleague starting the stack silently gets Sonar's defaults.
`scripts/sonar-setup.sh` is the fix — the gate as code, applied through the Web API, idempotent.

**Proven by destroying the server.** `docker compose -f compose.sonar.yaml down -v`, a fresh
start, then the script against a virgin instance:

```
OK    server is UP
OK    authenticated as 'admin'
OK    created project 'ecomdemo'
OK    created quality gate 'EcomDemo way'
--    new_coverage — already set          (inherited from Sonar's defaults)
--    new_violations — already set
--    new_duplicated_lines_density — already set
--    new_security_hotspots_reviewed — already set
OK    new_reliability_rating GT 1 — no new bug may be introduced
OK    new_security_rating GT 1 — no new vulnerability may be introduced
OK    attached 'EcomDemo way' to 'ecomdemo'
```

Re-running it produces all `--`, and the analysis against that rebuilt server is what produced §6
and §7.

**Three bugs were found in the script by running it rather than reading it**, and all three are
worth knowing:

1. It reported six blank failures. SonarQube answers a bad password with **401 and an empty
   body**, so checking the body alone produced `FAIL` with nothing after it. It now checks the
   HTTP status, and validates credentials once up front instead of failing six times.
2. The status was always empty. `status="$(api ...)"` runs the function in a **subshell**, so a
   global assigned inside it is discarded. The body now goes to a file and the status is the
   function's output — the same shape `request()` already uses in `smoke-test.sh`.
3. One condition failed with `{"errors":[{"msg":"Conversion = ')'"}]}`. That is SonarQube failing
   to render its own "already exists" message, because the metric is named *Duplicated Lines (%)
   on New Code* and the `%` breaks its formatter. Parsing error strings was the wrong approach;
   the script now reads the gate's existing conditions first. The gate name also contains a space,
   which had to be URL-encoded — `curl -G --data-urlencode`.

## 9. Smoke test — `scripts/smoke-test.sh`

No new checks this phase, as the phase file specifies. The existing script still passes against
the compose stack:

```
Summary: 125 passed, 0 failed, 0 skipped
SMOKE TEST PASSED
```

0 ERROR lines in the container log.

## 10. A note on the limits of coverage

96.1% is a good number and it is not a claim that the code is correct. Coverage measures which
lines *ran*, not whether anything *checked the result* — a suite of tests with no assertions can
reach 100%. The 81% **branch** figure is the more interesting one, because it counts decisions
rather than lines, and it is lower for a reason: several `else` paths are defensive and only
reachable through states the API does not permit.

What makes this suite worth something is not the percentage but the Phase 6 oversell race, the
Phase 8 ownership tests and the Phase 9 tampered-token test — none of which coverage can see.
The number is a floor, and the gate treats it as one.

## 11. Clean-up

- The SonarQube stack is left **running** (`ecomdemo-sonarqube`, `ecomdemo-sonar-db`) with the
  analysis for this branch. `docker compose -f compose.sonar.yaml down` stops it; `-v` deletes
  the history.
- The application stack is running and healthy at schema v6.
- The admin password and analysis token exist only on this machine, outside the repository.
- No stray Java processes.
