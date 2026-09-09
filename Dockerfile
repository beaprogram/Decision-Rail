FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package \
    && find target -maxdepth 1 -name '*.jar' ! -name '*.original' -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system decisionrail && useradd --system --gid decisionrail --home-dir /app decisionrail
COPY --from=build --chown=decisionrail:decisionrail /workspace/app.jar ./app.jar
USER decisionrail
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
