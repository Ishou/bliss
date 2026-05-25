# syntax=docker/dockerfile:1
# survey-api container image (ADR-0056). Multi-stage: JDK builder produces
# both the survey-api shadowJar (Ktor REST edge) and the survey-worker
# installDist layout (CLI subcommands invoked by Helm Jobs / CronJobs in
# survey/api/deploy/chart/). A single image ships both binaries so the
# seed-calibration post-install hook and the hourly retire CronJob can
# reuse the same digest as the api Deployment — `image.requireDigest`
# guarantees a single SHA per release.
#
# Base images pinned to digest per MANIFESTO; bump digest and tag together.
# Mirrors identity/api/Dockerfile so the four api services have the same
# build / runtime shape.

# ---- Stage 1: build ------------------------------------------------------
# eclipse-temurin:21.0.10_7-jdk-alpine-3.22
FROM eclipse-temurin:21.0.10_7-jdk-alpine-3.22@sha256:45df4ae087e53ba551d35d5142a2ba6837eaa52592882745ee4377b12c1655f2 AS build

WORKDIR /workspace

# Build scripts first so source-only edits keep the dep-resolve layer cached.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# settings.gradle.kts includes EVERY :grid:*, :game:*, :identity:*, and
# :survey:* module; Gradle's configuration phase requires each project
# directory (with its build.gradle.kts) to exist before any task can run.
# Copying just the build script per project is enough — sibling-context
# src/ is not needed because :survey:api:shadowJar and :survey:worker:installDist
# have no dependency on grid/, game/, or identity/.
COPY grid/domain/build.gradle.kts grid/domain/
COPY grid/application/build.gradle.kts grid/application/
COPY grid/infrastructure/build.gradle.kts grid/infrastructure/
COPY grid/api/build.gradle.kts grid/api/
COPY grid/worker/build.gradle.kts grid/worker/

COPY game/domain/build.gradle.kts game/domain/
COPY game/application/build.gradle.kts game/application/
COPY game/infrastructure/build.gradle.kts game/infrastructure/
COPY game/api/build.gradle.kts game/api/

COPY identity/domain/build.gradle.kts identity/domain/
COPY identity/application/build.gradle.kts identity/application/
COPY identity/infrastructure/build.gradle.kts identity/infrastructure/
COPY identity/api/build.gradle.kts identity/api/

COPY survey/domain/build.gradle.kts survey/domain/
COPY survey/application/build.gradle.kts survey/application/
COPY survey/infrastructure/build.gradle.kts survey/infrastructure/
COPY survey/api/build.gradle.kts survey/api/
COPY survey/worker/build.gradle.kts survey/worker/

# Source for the survey/ context only.
COPY survey/domain/src survey/domain/src
COPY survey/application/src survey/application/src
COPY survey/infrastructure/src survey/infrastructure/src
COPY survey/api/src survey/api/src
COPY survey/worker/src survey/worker/src

RUN chmod +x gradlew \
 && ./gradlew :survey:api:shadowJar :survey:worker:installDist --no-daemon --stacktrace

# ---- Stage 2: runtime ----------------------------------------------------
# eclipse-temurin:21.0.10_7-jre-alpine-3.22
FROM eclipse-temurin:21.0.10_7-jre-alpine-3.22@sha256:b08e4d2ef04cda3786f38cc92f96005c0e32361c353d109a6be0f03c3e9a157f

# wget is needed by HEALTHCHECK; declare explicitly to survive base-image swaps.
RUN apk add --no-cache wget

WORKDIR /app

# Ktor REST edge.
COPY --from=build /workspace/survey/api/build/libs/survey-api-*-all.jar /app/survey-api.jar
# Worker installDist layout: bin/survey-worker launcher + lib/*.jar classpath.
# The chart's seed-calibration Job + retire CronJob invoke
# `java -cp /app/lib/* com.bliss.survey.worker.MainKt`.
COPY --from=build /workspace/survey/worker/build/install/survey-worker/ /app/

# OpenTelemetry Java agent (ADR-0027). Auto-instruments Ktor + JDBC + the
# Ktor HTTP client at JVM start without code imports. Activated via
# JAVA_TOOL_OPTIONS in the chart's env values. Same pinned version as
# the other api images.
ADD --chmod=0644 \
    --checksum=sha256:bd01fea1304e8c8803fff827a0bdda02b2266742a85c62548053c6761474bb5b \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.27.0/opentelemetry-javaagent.jar \
    /app/otel.jar

# Run as non-root. K8s pod security context requires runAsNonRoot=true; without
# an explicit USER here, kubelet refuses to start with
# "container has runAsNonRoot and image will run as root".
RUN addgroup --system --gid 1000 ktor \
 && adduser --system --uid 1000 --ingroup ktor --no-create-home --shell /sbin/nologin ktor \
 && chown -R 1000:1000 /app
USER 1000:1000

# Port 7780 — grid/api owns 8080, game/api 8081, identity/api 8082.
EXPOSE 7780

# Container-level liveness probe. Kubernetes performs its own HTTP probe via
# the Helm chart; this is good hygiene for `docker run` outside the cluster.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O - http://localhost:7780/v1/health || exit 1

CMD ["java", "-jar", "/app/survey-api.jar"]

# OCI Image Annotations: link the GHCR package back to source and surface
# basic metadata (description, license) on the package page.
LABEL org.opencontainers.image.source="https://github.com/Ishou/bliss"
LABEL org.opencontainers.image.description="WordSparrow Survey JVM API (Ktor REST + worker CLI)"
LABEL org.opencontainers.image.licenses="FSL-1.1-MIT"
