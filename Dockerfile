FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /workspace
COPY zello/gradlew zello/build.gradle zello/settings.gradle ./
COPY zello/gradle ./gradle
RUN chmod +x gradlew
COPY zello/src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 zello \
    && useradd --uid 10001 --gid zello --no-create-home zello
WORKDIR /app
COPY --from=build --chown=zello:zello /workspace/build/libs/*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8080/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
