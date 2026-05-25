# syntax=docker/dockerfile:1
# game-api container image (ADR-0007 §3, ADR-0018). Multi-stage: JDK builder
# produces the shadowJar; JRE runtime image runs it. Base images pinned to
# digest per MANIFESTO; bump digest and tag together. Mirrors grid/api/Dockerfile
# so the two services have the same build / runtime shape.

# ---- Stage 1: build ------------------------------------------------------
# eclipse-temurin:21.0.10_7-jdk-alpine-3.22
FROM eclipse-temurin:21.0.10_7-jdk-alpine-3.22@sha256:45df4ae087e53ba551d35d5142a2ba6837eaa52592882745ee4377b12c1655f2 AS build

WORKDIR /workspace

# Build scripts first so source-only edits keep the dep-resolve layer cached.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# settings.gradle.kts includes EVERY :grid:* and :game:* module; Gradle's
# configuration phase requires each project directory (with its build.gradle.kts)
# to exist before any task can run, even tasks that don't depend on it.
# Copying just the build script per project is enough — src/ for grid/ is not
# needed because :game:api:shadowJar has no dependency on the grid context.
COPY grid/domain/build.gradle.kts grid/domain/
COPY grid/application/build.gradle.kts grid/application/
COPY grid/infrastructure/build.gradle.kts grid/infrastructure/
COPY grid/api/build.gradle.kts grid/api/
COPY grid/worker/build.gradle.kts grid/worker/

# Same gotcha for :game:* modules — every project directory must exist.
COPY game/domain/build.gradle.kts game/domain/
COPY game/application/build.gradle.kts game/application/
COPY game/infrastructure/build.gradle.kts game/infrastructure/
COPY game/api/build.gradle.kts game/api/

# Same gotcha for :identity:* modules introduced in ADR-0044.
COPY identity/domain/build.gradle.kts identity/domain/
COPY identity/application/build.gradle.kts identity/application/
COPY identity/infrastructure/build.gradle.kts identity/infrastructure/
COPY identity/api/build.gradle.kts identity/api/
# Same gotcha for :survey:* modules introduced in ADR-0056.
COPY survey/domain/build.gradle.kts survey/domain/
COPY survey/application/build.gradle.kts survey/application/
COPY survey/infrastructure/build.gradle.kts survey/infrastructure/
COPY survey/api/build.gradle.kts survey/api/

# Source for the game/ context only — shadowJar transitively pulls
# :game:domain, :game:application, :game:infrastructure.
COPY game/domain/src game/domain/src
COPY game/application/src game/application/src
COPY game/infrastructure/src game/infrastructure/src
COPY game/api/src game/api/src

RUN chmod +x gradlew && ./gradlew :game:api:shadowJar --no-daemon --stacktrace

# ---- Stage 2: runtime ----------------------------------------------------
# eclipse-temurin:21.0.10_7-jre-alpine-3.22
FROM eclipse-temurin:21.0.10_7-jre-alpine-3.22@sha256:b08e4d2ef04cda3786f38cc92f96005c0e32361c353d109a6be0f03c3e9a157f

# wget is needed by HEALTHCHECK; declare explicitly to survive base-image swaps.
RUN apk add --no-cache wget

WORKDIR /app

COPY --from=build /workspace/game/api/build/libs/game-api-*-all.jar /app/game-api.jar

# OpenTelemetry Java agent (ADR-0027). Auto-instruments Ktor + the
# Ktor HTTP client + WebSockets at JVM start without any code
# imports — manifesto's OTel-only rule satisfied because the agent
# attaches via -javaagent and emits OTLP, the open wire format.
# Activated via JAVA_TOOL_OPTIONS in the chart's env values; the
# Helm chart owns the OTLP endpoint, service name, and resource
# attributes. Same pinned version as grid/api (mirror).
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

# Port 8081 — grid/api owns 8080 in the cluster (ADR-0007 §3, ADR-0009).
# The two services co-exist on a single node without a port collision.
EXPOSE 8081

# Container-level liveness probe. Kubernetes performs its own HTTP probe via
# the Helm chart (Wave F PR #11); this is good hygiene for `docker run`
# outside the cluster.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O - http://localhost:8081/v1/health || exit 1

CMD ["java", "-jar", "/app/game-api.jar"]

# OCI Image Annotations: link the GHCR package back to source and surface
# basic metadata (description, license) on the package page.
LABEL org.opencontainers.image.source="https://github.com/Ishou/bliss"
LABEL org.opencontainers.image.description="WordSparrow Game JVM API (Ktor + WebSockets)"
LABEL org.opencontainers.image.licenses="FSL-1.1-MIT"
