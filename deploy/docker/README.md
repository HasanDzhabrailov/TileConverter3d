# Terrain Converter Web - Docker Deployment

Docker deployment for the Terrain Converter web stack.

## Components

- `../kotlin/terrain-web-ui/` – Kotlin/JS Compose web UI
- `../kotlin/terrain-web/` – Kotlin/Ktor backend

## Run with Docker Compose

From the repo root:

```bash
docker compose -f deploy/docker/docker-compose.yml up --build
```

For phone access while running in Docker, open the UI through the computer's actual Wi-Fi/LAN address, for example `http://<computer-lan-ip>:8080`. MBTiles TileJSON and style responses build their tile URLs from the request host, so copied mobile style links use the same address that requested them.

If auto-detection is not suitable for your network, override the public host explicitly:

```bash
TERRAIN_WEB_PUBLIC_HOST=<computer-lan-ip> docker compose -f deploy/docker/docker-compose.yml up --build
```

This builds the Kotlin/JS frontend bundle and Kotlin/Ktor backend, then serves the stack at `http://127.0.0.1:8080`.
The container publishes a health check against `http://127.0.0.1:8080/api/health` and stores runtime data in the `terrain-web-data` volume mounted at `/app/data`.
The backend defaults to `JAVA_TOOL_OPTIONS="-Xms512m -Xmx4g"`; increase or decrease that environment variable in Compose if the input dataset needs a different heap size.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TERRAIN_WEB_APP_NAME` | `terrain-converter-web` | Application name |
| `TERRAIN_WEB_HOST` | `0.0.0.0` | Bind host |
| `TERRAIN_WEB_PORT` | `8080` | Bind port |
| `TERRAIN_WEB_STORAGE_ROOT` | `/app/data` | Data storage path |
| `TERRAIN_WEB_FRONTEND_DIST` | `/app/terrain-web-ui` | Frontend assets path |
| `TERRAIN_WEB_PUBLIC_HOST` | (auto) | Public host for tile URLs |
| `JAVA_TOOL_OPTIONS` | `-Xms512m -Xmx4g` | JVM options |

## Backend API

### Job Conversion

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/jobs` | Create conversion job |
| GET | `/api/jobs` | List all jobs |
| GET | `/api/jobs/{jobId}` | Get job details |
| GET | `/api/jobs/{jobId}/logs` | Get job logs |
| WS | `/ws/jobs/{jobId}` | WebSocket for live updates |
| GET | `/api/jobs/{jobId}/downloads/{artifact}` | Download artifact |
| GET | `/api/jobs/{jobId}/terrain/{z}/{x}/{y}.png` | Terrain tile |
| GET | `/api/jobs/{jobId}/base/{z}/{x}/{y}` | Base MBTiles tile |
| GET | `/api/jobs/{jobId}/tilejson` | Job TileJSON |
| GET | `/api/jobs/{jobId}/style` | Job style |

### Uploaded MBTiles

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/mbtiles` | Upload MBTiles |
| GET | `/api/mbtiles` | List uploaded MBTiles |
| GET | `/api/mbtiles/{tilesetId}` | Get tileset info |
| GET | `/api/mbtiles/{tilesetId}/metadata` | MBTiles metadata |
| GET | `/api/mbtiles/{tilesetId}/tilejson` | TileJSON |
| GET | `/api/mbtiles/{tilesetId}/style` | Style |
| GET | `/api/mbtiles/{tilesetId}/style-mobile` | Mobile style |
| GET | `/api/mbtiles/{tilesetId}/{z}/{x}/{y}` | Tile (auto-format) |
| GET | `/api/mbtiles/{tilesetId}/{z}/{x}/{y}.{ext}` | Tile with extension |

## Storage Layout

- Jobs: `/app/data/jobs/<jobId>/...`
- Uploaded MBTiles: `/app/data/tilesets/<tilesetId>/...`

## Notes

- The Ktor backend runs conversion through Kotlin code directly; it does not spawn the CLI.
- Kotlin/KMP is the only retained implementation; Python and legacy Node.js frontends have been removed.
- The web UI is built with Kotlin/JS Compose Multiplatform.
