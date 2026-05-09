# syntax=docker/dockerfile:1
# grid-api container image (ADR-0007 §3). Multi-stage: JDK builder produces
# the shadowJar; JRE runtime image runs it. Base images pinned to digest per
# MANIFESTO; bump digest and tag together.

# ---- Stage 1: build ------------------------------------------------------
# eclipse-temurin:21.0.10_7-jdk-alpine-3.22
FROM eclipse-temurin:21.0.10_7-jdk-alpine-3.22@sha256:45df4ae087e53ba551d35d5142a2ba6837eaa52592882745ee4377b12c1655f2 AS build

WORKDIR /workspace

# Build scripts first so source-only edits keep the dep-resolve layer cached.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY grid/domain/build.gradle.kts grid/domain/
COPY grid/application/build.gradle.kts grid/application/
COPY grid/infrastructure/build.gradle.kts grid/infrastructure/
COPY grid/api/build.gradle.kts grid/api/
# settings.gradle.kts includes :game:* modules. Gradle's configuration step
# requires those project directories to exist before any task can run.
# :grid:api:shadowJar has no dependency on game/, so the build script alone
# is enough; src/ is intentionally not copied.
COPY game/domain/build.gradle.kts game/domain/
COPY game/application/build.gradle.kts game/application/
COPY game/infrastructure/build.gradle.kts game/infrastructure/
COPY game/api/build.gradle.kts game/api/

COPY grid/domain/src grid/domain/src
COPY grid/application/src grid/application/src
COPY grid/infrastructure/src grid/infrastructure/src
COPY grid/api/src grid/api/src

RUN chmod +x gradlew && ./gradlew :grid:api:shadowJar --no-daemon --stacktrace

# ---- Stage 2: runtime ----------------------------------------------------
# eclipse-temurin:21.0.10_7-jre-alpine-3.22
FROM eclipse-temurin:21.0.10_7-jre-alpine-3.22@sha256:b08e4d2ef04cda3786f38cc92f96005c0e32361c353d109a6be0f03c3e9a157f

# wget is needed by HEALTHCHECK; declare explicitly to survive base-image swaps.
RUN apk add --no-cache wget

WORKDIR /app

COPY --from=build /workspace/grid/api/build/libs/grid-api-*-all.jar /app/grid-api.jar

# OpenTelemetry Java agent (ADR-0027). Auto-instruments Ktor + JDBC +
# the Ktor HTTP client + WebSockets at JVM start without any code
# imports — the manifesto's "MUST NOT use vendor-specific
# instrumentation libraries in application code" rule is satisfied
# because the agent attaches via -javaagent and emits OTLP, the open
# wire format. Activated via JAVA_TOOL_OPTIONS in the chart's env
# values; the Helm chart owns the OTLP endpoint, service name, and
# resource attributes.
#
# Pinned to v2.10.0 — bump deliberately, not floating, per MANIFESTO
# Reproducible Builds. Sha256 verified against the GitHub Releases
# checksum file at download time:
#   https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar
ADD --chmod=0644 \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar \
    /app/otel.jar

# Run as non-root. K8s pod security context requires runAsNonRoot=true (see chart's
# values.yaml); without an explicit USER here, kubelet refused to start with
# "container has runAsNonRoot and image will run as root".
RUN addgroup --system --gid 1000 ktor \
 && adduser --system --uid 1000 --ingroup ktor --no-create-home --shell /sbin/nologin ktor \
 && chown -R 1000:1000 /app
USER 1000:1000

EXPOSE 8080

# Container-level liveness probe. Kubernetes performs its own HTTP
# probe via the Helm chart's readinessProbe / livenessProbe (ADR-0009);
# this is good hygiene for `docker run` outside the cluster.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O - http://localhost:8080/v1/health || exit 1

CMD ["java", "-jar", "/app/grid-api.jar"]

# OCI Image Annotations: link the GHCR package back to source and surface
# basic metadata (description, license) on the package page.
LABEL org.opencontainers.image.source="https://github.com/Ishou/bliss"
LABEL org.opencontainers.image.description="WordSparrow JVM API (Ktor)"
LABEL org.opencontainers.image.licenses="FSL-1.1-MIT"
