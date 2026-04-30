# Terrain Converter Web

Web workflow for uploading HGT files, running conversion jobs, downloading outputs, and previewing results.

## Components

- `frontend/` – React/Vite UI sources bundled into the Compose-managed stack
- `../kotlin/terrain-web/` – Kotlin/Ktor backend

## Run the Backend

Development:

```bash
gradle :terrain-web:run
```

Create launcher scripts:

```bash
gradle :terrain-web:installDist
```

Run installed launcher:

- Windows: `kotlin/terrain-web/build/install/terrain-web/bin/terrain-web.bat`
- Linux: `kotlin/terrain-web/build/install/terrain-web/bin/terrain-web`
- macOS: `kotlin/terrain-web/build/install/terrain-web/bin/terrain-web`

Environment variables:

```
TERRAIN_WEB_APP_NAME
TERRAIN_WEB_HOST
TERRAIN_WEB_PORT
TERRAIN_WEB_STORAGE_ROOT
TERRAIN_WEB_FRONTEND_DIST
TERRAIN_WEB_PUBLIC_HOST
```

Typical local values:

```
TERRAIN_WEB_HOST=0.0.0.0
TERRAIN_WEB_PORT=8080
TERRAIN_WEB_STORAGE_ROOT=web/data
TERRAIN_WEB_FRONTEND_DIST=web/frontend/dist
```

`TERRAIN_WEB_FRONTEND_DIST` is used only when the directory exists.

## Run the Web Stack with Compose

From the repo root:

```bash
docker compose -f web/docker-compose.yml up --build
```

This builds the frontend bundle and Kotlin/Ktor backend, then serves the stack at `http://127.0.0.1:8080`.
The container publishes a health check against `http://127.0.0.1:8080/api/health` and stores runtime data in the `terrain-web-data` volume mounted at `/app/web/data`.

## Frontend Development

From `web/frontend/`:

```bash
npm install
npm run dev
```

Dev server: `http://127.0.0.1:5173`

Proxies:

- `/api` → `http://127.0.0.1:8080`
- `/ws` → `ws://127.0.0.1:8080`

Build production bundle:

```bash
npm run build
```

When `web/frontend/dist` exists, the Ktor backend serves it at `/`.

## Windows Helper Script

From the repo root:

```bat
start-web.cmd
```

This starts the Kotlin backend on port `8080` and the Vite frontend on port `5173`. Windows-only.

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

### Storage Layout

- Jobs: `web/data/jobs/<jobId>/...`
- Uploaded MBTiles: `web/data/tilesets/<tilesetId>/...`

## Notes

- The Ktor backend runs conversion through Kotlin code directly; it does not spawn the CLI.
- Build the frontend first if you want the backend to serve the UI directly.
- `web/Dockerfile` and `web/docker-compose.yml` run the Kotlin/Ktor backend.
