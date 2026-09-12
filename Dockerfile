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
    && find target -maxdepth 1 -name '*.jar' ! -name '*.original' -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system decisionrail && useradd --system --gid decisionrail --home-dir /app decisionrail
COPY --from=build --chown=decisionrail:decisionrail /workspace/app.jar ./app.jar
USER decisionrail
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
