FROM gradle:8.7-jdk21 AS build
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs npm \
    && npm install --global yarn@1.22.17 \
    && rm -rf /var/lib/apt/lists/*

# Copy all project files
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY kotlin ./kotlin

# Build (frontend is built automatically as dependency of installDist)
RUN gradle :terrain-web:installDist --no-daemon -Pterrain.useSystemJsToolchain=true

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
