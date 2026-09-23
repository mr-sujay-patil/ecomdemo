# syntax=docker/dockerfile:1

# =============================================================================================
# EcomDemo — multi-stage build
# =============================================================================================
# An IMAGE is a stack of read-only layers plus a manifest: a filesystem and the metadata saying
# how to start a process in it. A CONTAINER is one running (or stopped) instance of an image,
# with a thin writable layer on top. Many containers share one image; nothing a container writes
# goes back into it. That is why the image is built once here and the compose file can then run
# it anywhere without a JDK, a Maven, or a matching Java version on the host.
#
# Two stages. The first has a JDK, Maven and the whole source tree; the second has a JRE and the
# application. Only the LAST stage becomes the published image, so the 556 MB of build tooling
# and the ~200 MB Maven repository are left behind — and, more importantly, so is the source
# code. A single-stage build would ship a compiler and the project's history to production.

# ---------------------------------------------------------------------------------------------
# Stage 1: build
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# LAYER CACHING is the reason this is not a single COPY. Docker caches each instruction's result
# and reuses it while the instruction and its inputs are unchanged; the first change invalidates
# every layer after it. Dependencies change rarely and source changes constantly, so the pom is
# copied and resolved FIRST. An ordinary code edit then reuses the cached dependency layer and
# the build starts from `mvn package`, instead of re-downloading ~200 MB every time.
#
# Since Phase 20 the build is a REACTOR, so the poms are copied as a set: the parent, which owns
# every version and plugin configuration, and one per module. `go-offline` then resolves the whole
# tree in a layer that a code edit does not invalidate, exactly as before.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common/pom.xml common/
COPY ecomdemo-app/pom.xml ecomdemo-app/
RUN ./mvnw -B -q dependency:go-offline

COPY common/src/ common/src/
COPY ecomdemo-app/src/ ecomdemo-app/src/

# -DskipTests, deliberately. Tests run in the build (`./mvnw clean verify`) and in CI, where a
# failure is visible and the report is readable. Running them here would need a Docker daemon
# INSIDE the build for Testcontainers, would double every image build, and would hide a failure
# behind a "failed to solve" message. An image is built from code that has already passed.
RUN ./mvnw -B -q package -DskipTests

# --- Extract the layered jar -----------------------------------------------------------------
# A Spring Boot fat jar is one 64 MB file, so a one-line code change would rewrite all 64 MB and
# push a whole new layer. `jarmode=tools extract --layers` splits it the way the application
# actually changes:
#
#   dependencies           ~64 MB, changes when the pom does           <- cached across builds
#   spring-boot-loader     ~300 KB, changes with the Boot version      <- cached
#   snapshot-dependencies  empty here
#   application            ~200 KB, our own classes and resources      <- the only layer that
#                                                                         usually changes
#
# Copied into the runtime image in that order, a rebuild after a code edit pushes ~200 KB instead
# of 64 MB. (`-Djarmode=layertools` was the Boot 2/3 spelling; Boot 3.3 replaced it with
# `jarmode=tools`, which this project uses.)
RUN java -Djarmode=tools -jar ecomdemo-app/target/*.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------------------------
# A JRE, not a JDK: nothing compiles at runtime, and a smaller image is less to pull and less
# attack surface. Alpine keeps it to ~287 MB against ~450 MB for the Ubuntu-based tag.
FROM eclipse-temurin:21-jre-alpine AS runtime

# --- Run as a non-root user ------------------------------------------------------------------
# Containers run as root by default, and a container's root IS the host's root: the isolation is
# namespaces, not a different user table. So a container escape, or a bind-mounted host
# directory, hands over root privileges for no reason at all — this application never needs to
# install a package, write to /etc or bind a port below 1024.
#
# The uid is fixed rather than left to the distribution, because a bind-mounted volume's files
# are owned by a NUMBER; a uid that drifts between rebuilds turns into "permission denied" on a
# mount that worked yesterday.
RUN addgroup --system --gid 1001 ecomdemo \
    && adduser --system --uid 1001 --ingroup ecomdemo --no-create-home ecomdemo

WORKDIR /app

# Copied newest-changing LAST, so the expensive layers stay cached. --chown avoids a second
# full-size layer that would exist only to change file ownership.
COPY --from=build --chown=ecomdemo:ecomdemo /build/extracted/dependencies/ ./
COPY --from=build --chown=ecomdemo:ecomdemo /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=ecomdemo:ecomdemo /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=ecomdemo:ecomdemo /build/extracted/application/ ./

# Where the batch jobs (Phase 14) read uploads and write reports and error files.
#
# NOT under /app. The working directory is owned by root and the application runs as `ecomdemo`,
# so the first upload would fail on mkdir with a permission error that looks nothing like a
# permission error by the time it reaches the client. Created here, owned by the runtime user,
# and mounted as a named volume in compose.
#
# It has to be a volume rather than container-local scratch: a restart re-reads the SAME staged
# file by path, so an import that failed before a container was replaced could not be resumed if
# its input had gone with the container.
RUN mkdir -p /var/lib/ecomdemo/batch \
    && chown -R ecomdemo:ecomdemo /var/lib/ecomdemo
ENV BATCH_DIR=/var/lib/ecomdemo/batch

USER ecomdemo:ecomdemo

EXPOSE 8080

# --- JVM settings for a container ------------------------------------------------------------
# MaxRAMPercentage is the one that matters. A modern JVM reads the container's memory limit
# rather than the host's, but its default heap is 25% of it — so a 512 MB container runs a 128 MB
# heap and spends its life in GC while 384 MB sits unused. 75% leaves room for the parts of a JVM
# that are NOT heap: metaspace, thread stacks, code cache, direct buffers. Setting -Xmx instead
# would hard-code a number that is wrong the moment the limit changes.
#
# MaxRAMPercentage, not MinRAMPercentage: the "Min" one applies only below ~250 MB of available
# memory, which is a trap worth knowing about.
#
# ExitOnOutOfMemoryError: a JVM that has exhausted its heap is not going to recover, and a
# container that keeps answering health checks while failing every request is worse than one that
# dies and gets restarted. Restarting is what an orchestrator is for.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# --- Health check -----------------------------------------------------------------------------
# Compose uses this to decide when the service is genuinely ready, not merely started.
#
# READINESS, not liveness, and not /actuator/health. Which one is chosen here decides what
# `condition: service_healthy` in compose.yaml actually waits for:
#   /actuator/health            everything Spring knows about, Redis included - so the app would
#                               be reported unhealthy over a cache outage it can survive
#   /actuator/health/liveness   the JVM's own lifecycle only - true almost immediately, so
#                               dependent services would start before the database was reachable
#   /actuator/health/readiness  the lifecycle flag AND the DataSource: "this process can serve a
#                               request right now", which is the question being asked
#
# Anonymous by design (SecurityConfig permits the health endpoint) because a HEALTHCHECK has no
# credentials, and the body it gets back is the bare {"status":"UP"} - show-details is
# `when-authorized`. A DOWN answer is HTTP 503, which wget exits non-zero on, so the shape below
# needs no JSON parsing.
#
# Phase 14 and earlier probed /api/products instead, because Actuator did not exist yet.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1

# `sh -c` so $JAVA_OPTS is expanded; exec so the JVM becomes PID 1 and receives SIGTERM directly,
# which is what lets `docker compose down` shut Spring down gracefully instead of killing it
# after a ten-second timeout.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
