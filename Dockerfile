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
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/

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
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

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
# Compose uses this to decide when the service is genuinely ready, not merely started. The
# catalogue endpoint is used rather than an actuator probe because Actuator arrives in Phase 15;
# it is public (Phase 8 kept the shop window open), so no token is needed, and it touches the
# database, which means a pass really does mean "the whole stack answers".
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -q -O /dev/null http://localhost:8080/api/products || exit 1

# `sh -c` so $JAVA_OPTS is expanded; exec so the JVM becomes PID 1 and receives SIGTERM directly,
# which is what lets `docker compose down` shut Spring down gracefully instead of killing it
# after a ten-second timeout.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
