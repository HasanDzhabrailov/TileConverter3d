# Terrain Converter

Converts SRTM/HGT elevation tiles into MapLibre-compatible Terrain-RGB outputs.

Outputs:

- `terrain-rgb.mbtiles` – SQLite database with terrain tiles
- `terrain/{z}/{x}/{y}.png` – XYZ tile pyramid
- `terrain/tiles.json` – TileJSON for `raster-dem` source
- `style.json` – MapLibre style document

Terrain data is produced as a separate `raster-dem` source. The project does not merge DEM data into existing base map MBTiles.

## Requirements

- JDK 21
- Gradle in PATH (`gradle` command). This repo does not include `gradlew`.

For frontend development:

- Node.js 18+
- npm

## Project Structure

```
kotlin/
  terrain-core/    Shared Kotlin conversion logic (KMP)
  terrain-cli/     Kotlin CLI application
  terrain-web/     Kotlin/Ktor backend application
web/
  frontend/        React/Vite UI sources
  docker-compose.yml
  Dockerfile
docs/
  terrain-pipeline.md
  kotlin-migration-status.md
```

## Build

Run all tests:

```bash
gradle :terrain-core:test
gradle :terrain-cli:test
gradle :terrain-web:test
```

Create launcher scripts:

```bash
gradle :terrain-cli:installDist
gradle :terrain-web:installDist
```

## Run the CLI

### Quick Start

Run with Gradle:

```bash
gradle :terrain-cli:run --args="INPUT --minzoom 8 --maxzoom 12"
```

`INPUT` can be one `.hgt` file or a directory containing `.hgt` files.

### Cross-Platform Launchers

```bash
gradle :terrain-cli:installDist
```

Platform launchers:

| Platform | Path |
|----------|------|
| Windows | `kotlin/terrain-cli/build/install/terrain-converter/bin/terrain-converter.bat` |
| Linux | `kotlin/terrain-cli/build/install/terrain-converter/bin/terrain-converter` |
| macOS | `kotlin/terrain-cli/build/install/terrain-converter/bin/terrain-converter` |

### CLI Options

```
Arguments:
  inputs                         HGT files or directories

Options:
  -o, --output PATH              Output MBTiles path (default: terrain-rgb.mbtiles)
  --output-mbtiles PATH          Alias for --output
  --tile-root PATH               Output tile directory root (default: terrain)
  --tilejson PATH                Output TileJSON path (default: terrain/tiles.json)
  --style-json PATH              Output style.json path (default: style.json)
  --tiles-url URL                Public terrain tile URL template
                                 (default: http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png)
  --minzoom INT                  Minimum output zoom (default: 8)
  --maxzoom INT                  Maximum output zoom (default: 12)
  --bbox WEST SOUTH EAST NORTH   Optional manual output bounds
  --tile-size INT                Output tile size in pixels (default: 256)
  --scheme {xyz,tms}             Output TileJSON/style scheme (default: xyz)
  --encoding {mapbox,terrarium}  Terrain encoding (default: mapbox)
  --name NAME                    MBTiles metadata name (default: terrain-dem)
  --workers INT                  Worker process count for tile rendering
  -h, --help                     Show help message and exit
```

### Examples

**Windows (PowerShell):**
```powershell
gradle :terrain-cli:installDist
.\kotlin\terrain-cli\build\install\terrain-converter\bin\terrain-converter.bat `
  data\hgt --minzoom 8 --maxzoom 12
```

**Windows (CMD):**
```cmd
gradle :terrain-cli:installDist
.\kotlin\terrain-cli\build\install\terrain-converter\bin\terrain-converter.bat ^
  data\hgt --minzoom 8 --maxzoom 12
```

**Linux/macOS:**
```bash
gradle :terrain-cli:installDist
./kotlin/terrain-cli/build/install/terrain-converter/bin/terrain-converter \
  data/hgt --minzoom 8 --maxzoom 12
```

**With explicit options:**
```bash
gradle :terrain-cli:run --args="data/hgt \
  --output out/terrain-rgb.mbtiles \
  --tile-root out/terrain \
  --tilejson out/terrain/tiles.json \
  --style-json out/style.json \
  --tiles-url http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png \
  --minzoom 8 --maxzoom 12"
```

**Manual bounding box:**
```bash
gradle :terrain-cli:run --args="data/hgt \
  --bbox 5.0 45.0 15.0 55.0 \
  --minzoom 8 --maxzoom 12"
```

**TMS scheme:**
```bash
gradle :terrain-cli:run --args="data/hgt --scheme tms --minzoom 8 --maxzoom 12"
```

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error (missing inputs, unhandled exception) |
| 2 | Validation error (invalid arguments, bad zoom range) |
| 3 | Input error (file not found, invalid extension) |

## Run the Backend

Development:

```bash
gradle :terrain-web:run
```

After `installDist`:

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

Typical values:

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

Compose builds the frontend bundle from `web/frontend/` and the Kotlin/Ktor backend from `kotlin/terrain-web/`, then serves both on `http://127.0.0.1:8080`.
The container is considered healthy when `GET /api/health` returns `200 OK`.
The backend defaults to `JAVA_TOOL_OPTIONS="-Xms512m -Xmx4g"`; override that environment variable if your HGT dataset needs a different heap size.

Health check:

```
GET http://127.0.0.1:8080/api/health
```

Persistent job data is stored in the named Docker volume `terrain-web-data`, mounted at `/app/web/data` inside the container.

## Frontend Development

The frontend is a React/Vite UI bundled into the Compose-managed web stack.

From `web/frontend/`:

```bash
npm install
npm run dev
```

Dev server: `http://127.0.0.1:5173`

Proxies:

- `/api` → `http://127.0.0.1:8080`
- `/ws` → `ws://127.0.0.1:8080`

Build the production bundle:

```bash
npm run build
```

When `web/frontend/dist` exists, the Ktor backend serves it at `/`.

## Use the Generated Outputs

CLI outputs:

- `terrain-rgb.mbtiles` – MBTiles database for distribution
- `terrain/{z}/{x}/{y}.png` – XYZ tiles for direct serving
- `terrain/tiles.json` – TileJSON for `raster-dem` source
- `style.json` – Ready-to-load MapLibre style

Default terrain tile URL:

```
http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png
```

If serving from a different URL, set `--tiles-url` when running the CLI.

To serve the `terrain/` directory without the backend, use any static HTTP server that preserves the `{z}/{x}/{y}.png` layout.

## Backend Workflow

Job conversion API:

1. `POST /api/jobs` with `hgt_files` and optional `base_mbtiles`
2. Track status: `GET /api/jobs` or `GET /api/jobs/{jobId}`
3. Read logs: `GET /api/jobs/{jobId}/logs` or `WS /ws/jobs/{jobId}`
4. Download: `GET /api/jobs/{jobId}/downloads/{artifact}`
5. Terrain tiles: `GET /api/jobs/{jobId}/terrain/{z}/{x}/{y}.png`
6. Job TileJSON/Style: `GET /api/jobs/{jobId}/tilejson` and `/style`

Uploaded MBTiles API:

1. `POST /api/mbtiles`
2. Metadata: `GET /api/mbtiles/{tilesetId}/metadata`
3. TileJSON: `GET /api/mbtiles/{tilesetId}/tilejson`
4. Style: `GET /api/mbtiles/{tilesetId}/style`
5. Mobile style: `GET /api/mbtiles/{tilesetId}/style-mobile`
6. Tiles: `GET /api/mbtiles/{tilesetId}/{z}/{x}/{y}` or `/{y}.{ext}`

Storage layout:

- Jobs: `web/data/jobs/<jobId>/...`
- Uploaded MBTiles: `web/data/tilesets/<tilesetId>/...`

## Limitations and Compatibility

- Supported HGT grid sizes: `1201×1201` and `3601×3601`
- Mixed `1201` and `3601` inputs in one run are not supported
- HGT filenames must match SRTM naming (e.g., `N45E006.hgt`)
- Terrain encoding: `mapbox` (default), `terrarium` supported
- Filesystem tiles are written as XYZ
- MBTiles rows are stored as TMS internally
- Backend flips `y` only when serving job tiles created with `scheme=tms`
- Legacy Python code is archived in `archive/legacy-python/`
- Runtime, scripts, Docker, and docs are Kotlin-only

## Related Docs

- `web/README.md` – Web stack details
- `docs/terrain-pipeline.md` – Conversion pipeline
- `docs/kotlin-migration-status.md` – Migration status
