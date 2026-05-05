FROM node:20-bookworm-slim AS node

FROM gradle:8.7-jdk21 AS build
WORKDIR /app
ENV GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"

COPY --from=node /usr/local /usr/local
RUN rm -f /usr/local/bin/yarn /usr/local/bin/yarnpkg \
    && npm install --global yarn@1.22.17 \
    && node --version \
    && yarn --version

# Copy all project files
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY .yarnrc ./
COPY kotlin ./kotlin

# Build (frontend is built automatically as dependency of installDist)
RUN for attempt in 1 2 3; do \
        gradle :terrain-web:installDist --no-daemon -Pterrain.useSystemJsToolchain=true && break; \
        if [ "$attempt" = "3" ]; then exit 1; fi; \
        sleep 15; \
    done

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
