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

FROM eclipse-temurin:21-jre-jammy AS application
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

# The Render target reuses the application built above. Its edge is in the same container because
# a free Render service cannot receive private-network traffic from a separate edge service.
# Official multi-platform Caddy 2.11.4 index, resolved from Docker Hub on 2026-09-18.
FROM caddy:2.11.4-alpine@sha256:de23def33b17fb5d1290b0f6c2add1d70780e52341896c00a4c8a2a2fe9d355e AS render-caddy

FROM application AS render
COPY --from=render-caddy /usr/bin/caddy /usr/local/bin/caddy
COPY --chown=decisionrail:decisionrail deploy/render/Caddyfile /etc/caddy/Caddyfile
COPY --chown=decisionrail:decisionrail deploy/render/entrypoint.sh /app/render-entrypoint.sh
RUN chmod 755 /app/render-entrypoint.sh
# Heap is one part of the 512 MiB budget: leave space for metaspace, stacks, direct buffers and Caddy.
# Go's memory limit is a GC target, not a hard RSS cap. Verify the whole container under its real limit.
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx224m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss512k -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=48m -XX:+ExitOnOutOfMemoryError" \
    GOMEMLIMIT=32MiB \
    PORT=10000 \
    XDG_CONFIG_HOME=/tmp/caddy/config \
    XDG_DATA_HOME=/tmp/caddy/data
EXPOSE 10000
ENTRYPOINT ["/app/render-entrypoint.sh"]

# An ordinary build still produces the existing standalone application image.
FROM application AS default
