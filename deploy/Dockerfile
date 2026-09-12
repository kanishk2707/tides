# AETHER TIDES match server
#
# Stage 1 builds the fat jar with the same Gradle wrapper the repo uses; stage 2 is a minimal
# JRE. The result is ~200 MB and needs nothing but the four environment variables in .env.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :server:fatJar -x test

FROM eclipse-temurin:17-jre
# curl is only here for the container healthcheck below; the server itself needs nothing.
RUN apt-get update && apt-get install -y --no-install-recommends curl     && rm -rf /var/lib/apt/lists/*     && useradd --system --create-home --shell /usr/sbin/nologin aether
WORKDIR /app
COPY --from=build /src/server/build/libs/aether-tides-server-*.jar /app/server.jar
USER aether
EXPOSE 7788 7789
ENV PORT=7788 HEALTH_PORT=7789 JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=20"
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s CMD curl -fsS http://127.0.0.1:7789/healthz || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/server.jar"]
