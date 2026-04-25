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
COPY grid/infrastructure/build.gradle.kts grid/infrastructure/
COPY grid/api/build.gradle.kts grid/api/

COPY grid/domain/src grid/domain/src
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

EXPOSE 8080

# Container-level liveness probe. Fly performs its own HTTP probe via
# fly.toml (workstream 2); this is good hygiene for `docker run`.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O - http://localhost:8080/v1/health || exit 1

CMD ["java", "-jar", "/app/grid-api.jar"]
