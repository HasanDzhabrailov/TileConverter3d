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

This is the portable path for Windows, Linux, and macOS. For phone access, open the UI through the host computer's LAN address, for example `http://192.168.1.20:8080` or `http://10.211.46.51:8080`. When the UI is opened through that address, the backend uses the request host and generated tile, TileJSON, style, and mobile style links update from `/api/server-info` automatically.

Docker bridge networking does not expose the host computer's Wi-Fi/LAN IP to the container in a reliable cross-platform way. The backend therefore does not publish Docker's internal `172.x` container address as a mobile address. If you open the UI via `localhost`, phone links cannot be inferred portably; reopen it via the host LAN IP or set `TERRAIN_WEB_LAN_HOST` explicitly.

On Windows, an optional convenience launcher can keep the host LAN IP updated automatically. It is not required for cross-platform use:

```powershell
powershell -ExecutionPolicy Bypass -File deploy/docker/run-lan.ps1
```

For phone access while running in Docker, open the UI through one of the automatically shown mobile addresses. The server publishes an HTTP LAN domain based on the detected LAN IP, for example `http://192-168-1-20.sslip.io:8080`, plus a generated `.local` name when available. The UI tests these addresses and copy buttons use the first reachable one for tile, TileJSON, style, and mobile style URLs.

The `sslip.io` address is not hardcoded to your network: it is generated from the detected LAN IP and resolves back to that IP through wildcard DNS. Docker on Windows cannot reliably see the host Wi-Fi IP from inside the Linux container, so `run-lan.ps1` detects it on the host and passes it as `TERRAIN_WEB_LAN_HOST`. The `.local` address is built from the host computer name and remains a fallback only. Docker Compose passes `COMPUTERNAME` from Windows and `HOSTNAME` from Linux/macOS into the container.

If the generated `.local` name is not the one you want, override it explicitly:

```bash
TERRAIN_WEB_LOCAL_DOMAIN=my-tiles.local docker compose -f deploy/docker/docker-compose.yml up --build
```

If neither generated domain resolves on a device, use the shown `http://<computer-lan-ip>:8080` fallback from the same Wi-Fi/LAN.

If auto-detection is not suitable for your network, override the public host explicitly:

```bash
TERRAIN_WEB_PUBLIC_HOST=<computer-lan-ip> docker compose -f deploy/docker/docker-compose.yml up --build
```

For a real public HTTP domain, point DNS and a reverse proxy/tunnel at the container, then set the full external URL:

```bash
TERRAIN_WEB_PUBLIC_URL=http://tiles.example.com docker compose -f deploy/docker/docker-compose.yml up --build
```

`TERRAIN_WEB_PUBLIC_URL` is used as-is for generated tile URLs, TileJSON, styles, and copied links. Docker Compose itself does not create a public domain; use DNS plus a reverse proxy such as Caddy, Nginx, Traefik, or a tunnel provider.

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
| `TERRAIN_WEB_PUBLIC_URL` | (auto) | Full public base URL, for example `http://tiles.example.com` |
| `TERRAIN_WEB_PUBLIC_HOST` | (auto) | Public host for tile URLs |
| `TERRAIN_WEB_LAN_HOST` | (auto) | Host LAN IPv4 used to generate mobile URLs |
| `TERRAIN_WEB_LAN_HOST_FILE` | `/app/config/lan-host.txt` | Runtime file for dynamically updated Docker host LAN IPv4 |
| `TERRAIN_WEB_LOCAL_DOMAIN` | (auto) | HTTP LAN domain, for example `my-tiles.local` |
| `TERRAIN_WEB_HOST_COMPUTERNAME` | from host | Host computer name passed into Docker |
| `TERRAIN_WEB_HOST_HOSTNAME` | from host | Host hostname passed into Docker |
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
