FROM node:20-bookworm AS frontend-build
WORKDIR /app/web/frontend
COPY web/frontend/package.json web/frontend/package-lock.json web/frontend/tsconfig.json web/frontend/vite.config.ts ./
RUN npm ci
COPY web/frontend/index.html ./
COPY web/frontend/src ./src
RUN npm run build

FROM gradle:8.7-jdk21 AS kotlin-build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY kotlin ./kotlin
RUN gradle :terrain-web:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=kotlin-build /app/kotlin/terrain-web/build/install/terrain-web ./terrain-web
COPY --from=frontend-build /app/web/frontend/dist ./web/frontend/dist
EXPOSE 8080
ENV TERRAIN_WEB_HOST=0.0.0.0
ENV TERRAIN_WEB_PORT=8080
ENV TERRAIN_WEB_STORAGE_ROOT=/app/web/data
ENV TERRAIN_WEB_FRONTEND_DIST=/app/web/frontend/dist
ENV JAVA_TOOL_OPTIONS="-Xms512m -Xmx4g"
CMD ["/app/terrain-web/bin/terrain-web"]
