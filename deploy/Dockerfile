# Build context: repository root.  docker build -f deploy/Dockerfile -t hejje .
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY server/build.gradle.kts server/settings.gradle.kts ./
RUN gradle dependencies --no-daemon -q > /dev/null 2>&1 || true
COPY server/src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --system hejje && useradd --system --gid hejje --home /app --shell /usr/sbin/nologin hejje \
    && mkdir -p /app /data && chown -R hejje:hejje /app /data
WORKDIR /app
COPY --from=build --chown=hejje:hejje /src/build/libs/*.jar /app/hejje.jar
USER hejje
ENV SPRING_PROFILES_ACTIVE=prod HEJJE_DATA_DIR=/data JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080 8081
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl -fsS http://localhost:8080/api/v1/server/ping || exit 1
ENTRYPOINT ["java", "-jar", "/app/hejje.jar"]
