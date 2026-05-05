FROM gradle:8.7-jdk21 AS build
WORKDIR /app

# Copy all project files
COPY build.gradle.kts settings.gradle.kts ./
COPY kotlin ./kotlin

# Build frontend first (includes npm install)
RUN gradle -p kotlin/terrain-web-ui syncFrontendDist --no-daemon

# Build backend
RUN gradle :terrain-web:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/kotlin/terrain-web/build/install/terrain-web ./terrain-web
COPY --from=build /app/kotlin/terrain-web-ui/build/frontendDist ./terrain-web-ui
EXPOSE 8080
ENV TERRAIN_WEB_HOST=0.0.0.0
ENV TERRAIN_WEB_PORT=8080
ENV TERRAIN_WEB_STORAGE_ROOT=/app/data
ENV TERRAIN_WEB_FRONTEND_DIST=/app/terrain-web-ui
ENV JAVA_TOOL_OPTIONS="-Xms512m -Xmx4g"
CMD ["/app/terrain-web/bin/terrain-web"]
