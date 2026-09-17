FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
# The dashboard is part of the application, so its source and lockfile are part of the build context.
# node_modules is excluded by .dockerignore: the build installs from the lockfile instead, so the
# image never depends on a host machine's installed packages.
COPY frontend/package.json frontend/package-lock.json ./frontend/
COPY frontend/tsconfig.json frontend/tsconfig.app.json frontend/tsconfig.node.json ./frontend/
COPY frontend/vite.config.ts frontend/vitest.config.ts frontend/eslint.config.js frontend/index.html ./frontend/
COPY frontend/src ./frontend/src
COPY src ./src
# The Maven build downloads its own pinned Node, so the image's toolchain matches a local build.
RUN mvn --batch-mode --no-transfer-progress -DskipTests package \
    && find target -maxdepth 1 -name '*.jar' ! -name '*.original' -exec cp {} /workspace/app.jar \; \
    && ls src/main/resources/db/migration | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | tail -1 > /workspace/latest-migration

FROM eclipse-temurin:21-jre-jammy
# Which revision this image is. Supplied by the release workflow (and by deploy/bin/build-image.sh for a
# local build); reported by GET /actuator/info so a running instance can be matched to a commit and an
# image without exposing anything about its configuration. Absent values read as "unknown" there.
ARG APP_COMMIT=unknown
ARG APP_IMAGE=unknown
ARG APP_BUILT_AT=unknown
LABEL org.opencontainers.image.source="https://github.com/beaprogram/Decision-Rail" \
      org.opencontainers.image.revision="$APP_COMMIT" \
      org.opencontainers.image.created="$APP_BUILT_AT" \
      org.opencontainers.image.title="DecisionRail" \
      org.opencontainers.image.description="Synthetic payment decisioning: authorization, capture, returns, ledger, replay and shadow evaluation. No real money."
ENV APP_COMMIT=$APP_COMMIT APP_IMAGE=$APP_IMAGE APP_BUILT_AT=$APP_BUILT_AT
WORKDIR /app
RUN groupadd --system decisionrail && useradd --system --gid decisionrail --home-dir /app decisionrail
COPY --from=build --chown=decisionrail:decisionrail /workspace/app.jar ./app.jar
# The newest migration this image carries, readable without starting it: deploy/bin/rollback.sh
# compares it with what the database has applied before switching images.
COPY --from=build --chown=decisionrail:decisionrail /workspace/latest-migration ./latest-migration
USER decisionrail
EXPOSE 8080
# The JVM sizes itself from the container's memory limit; the percentage leaves room for the JIT,
# metaspace and threads inside a small limit rather than letting the heap take all of it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
