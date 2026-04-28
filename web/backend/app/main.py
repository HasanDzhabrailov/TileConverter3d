from __future__ import annotations

import os
import re
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path
import socket
import json

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles

from terrain_converter.bbox import Bounds
from terrain_converter.style_json import build_style
from terrain_converter.tilejson import build_tilejson

from .config import Settings
from .jobs import JobManager
from .mbtiles_server import MBTilesServer
from .models import BBox, JobOptions, MBTilesTileset, ServerAddress, ServerInfo
from .storage import Storage
from .websocket_manager import WebSocketManager


def _is_usable_host(host: str) -> bool:
    if not host:
        return False
    if host in {".", "0.0.0.0", "localhost", "::", "::1"}:
        return False
    return True


def _resolve_public_host(request: Request) -> str:
    configured_host = os.getenv("TERRAIN_WEB_PUBLIC_HOST", "").strip()
    if _is_usable_host(configured_host):
        return configured_host

    host = request.url.hostname or "127.0.0.1"
    if host not in {"127.0.0.1", "localhost", "::1"} and _is_usable_host(host):
        return host

    # Prefer the OS-selected outbound interface over hostname lookup so we do not
    # accidentally publish WSL/Hyper-V/VPN adapter addresses.
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("8.8.8.8", 80))
            candidate = probe.getsockname()[0]
            if candidate and not candidate.startswith("127.") and _is_usable_host(candidate):
                return candidate
    except OSError:
        pass

    for candidate in _ipconfig_ipv4_candidates():
        return candidate

    try:
        candidates = socket.getaddrinfo(socket.gethostname(), None, family=socket.AF_INET)
    except OSError:
        candidates = []

    for entry in candidates:
        candidate = entry[4][0]
        if candidate and not candidate.startswith("127.") and _is_usable_host(candidate):
            return candidate
    return host


def _ipconfig_ipv4_candidates() -> list[str]:
    try:
        command = ["cmd", "/c", "ipconfig"] if os.name == "nt" else ["ipconfig"]
        output = subprocess.check_output(command, text=True, encoding="utf-8", errors="ignore")
    except (OSError, subprocess.SubprocessError):
        return []

    addresses = re.findall(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", output)
    candidates: list[str] = []
    for address in addresses:
        if address.startswith("127."):
            continue
        if address.startswith("172.31."):
            continue
        if address.startswith("172.30."):
            continue
        if address.startswith("172.29."):
            continue
        if address.startswith("169.254."):
            continue
        if address.startswith("100."):
            continue
        if not _is_private_ipv4(address):
            continue
        if address not in candidates:
            candidates.append(address)
    return candidates


def _is_private_ipv4(address: str) -> bool:
    octets = address.split(".")
    if len(octets) != 4:
        return False
    try:
        first, second = int(octets[0]), int(octets[1])
    except ValueError:
        return False
    if first == 10:
        return True
    if first == 192 and second == 168:
        return True
    if first == 172 and 16 <= second <= 31:
        return True
    return False


def _public_base_url(request: Request) -> str:
    host = _resolve_public_host(request)
    port = request.url.port
    default_port = 443 if request.url.scheme == "https" else 80
    port_suffix = "" if port in {None, default_port} else f":{port}"
    return f"{request.url.scheme}://{host}{port_suffix}"


def _build_base_url(request: Request, host: str) -> str:
    port = request.url.port
    default_port = 443 if request.url.scheme == "https" else 80
    port_suffix = "" if port in {None, default_port} else f":{port}"
    return f"{request.url.scheme}://{host}{port_suffix}"


def _server_info(request: Request) -> ServerInfo:
    request_host = request.url.hostname or "127.0.0.1"
    public_host = _resolve_public_host(request)
    addresses: list[ServerAddress] = [
        ServerAddress(
            id="mobile",
            label="Mobile / Wi-Fi",
            host=public_host,
            base_url=_build_base_url(request, public_host),
            description="Use this address from a phone or another device in the same local network.",
        ),
        ServerAddress(
            id="localhost",
            label="This computer",
            host="127.0.0.1",
            base_url=_build_base_url(request, "127.0.0.1"),
            description="Use this address only on the same computer where the server is running.",
        ),
    ]
    if request_host not in {public_host, "127.0.0.1", "localhost", "::1"}:
        addresses.append(
            ServerAddress(
                id="request-host",
                label="Current browser host",
                host=request_host,
                base_url=_build_base_url(request, request_host),
                description="This is the host from which the current browser opened the UI.",
            )
        )
    return ServerInfo(addresses=addresses)


def _guess_mbtiles_source_type(filename: str, metadata: dict[str, str]) -> str:
    hints = " ".join([filename, metadata.get("name", ""), metadata.get("description", "")]).lower()
    if "terrain-rgb" in hints or "terrain" in hints or "dem" in hints:
        return "raster-dem"
    return "raster"


def _build_mbtiles_tileset_payload(*, request: Request, tileset_id: str, filename: str, created_at: datetime, server: MBTilesServer, source_type: str) -> MBTilesTileset:
    public_base_url = _public_base_url(request)
    tile_format = server.get_format()
    tile_path = f"/api/mbtiles/{tileset_id}/{{z}}/{{x}}/{{y}}.{tile_format}"
    tilejson_path = f"/api/mbtiles/{tileset_id}/tilejson"
    style_path = f"/api/mbtiles/{tileset_id}/style"
    mobile_style_path = f"/api/mbtiles/{tileset_id}/style-mobile"
    return MBTilesTileset(
        id=tileset_id,
        filename=filename,
        created_at=created_at,
        tile_url_template=tile_path,
        public_tile_url_template=f"{public_base_url}{tile_path}",
        tilejson_url=tilejson_path,
        public_tilejson_url=f"{public_base_url}{tilejson_path}",
        style_url=style_path,
        public_style_url=f"{public_base_url}{style_path}",
        mobile_style_url=mobile_style_path,
        public_mobile_style_url=f"{public_base_url}{mobile_style_path}",
        name=server.get_name(),
        attribution=server.get_attribution(),
        source_type=source_type,
        tile_format=tile_format,
        minzoom=server.get_minzoom(),
        maxzoom=server.get_maxzoom(),
        bounds=server.get_bounds(),
        view=server.get_view(),
    )


def _build_mbtiles_tilejson(tileset: MBTilesTileset) -> dict[str, object]:
    bounds = tileset.bounds or BBox(west=-180.0, south=-85.05112878, east=180.0, north=85.05112878)
    minzoom = tileset.minzoom if tileset.minzoom is not None else 0
    maxzoom = tileset.maxzoom if tileset.maxzoom is not None else 14
    if tileset.source_type == "raster-dem":
        return build_tilejson(
            Bounds(**bounds.model_dump()),
            min_zoom=minzoom,
            max_zoom=maxzoom,
            tiles_url=tileset.public_tile_url_template,
            name=tileset.name or tileset.filename,
            scheme="xyz",
            encoding="mapbox",
            tile_size=256,
        )
    center_lon = (bounds.west + bounds.east) / 2
    center_lat = (bounds.south + bounds.north) / 2
    return {
        "tilejson": "3.0.0",
        "name": tileset.name or tileset.filename,
        "type": "raster",
        "scheme": "xyz",
        "format": tileset.tile_format,
        "tiles": [tileset.public_tile_url_template],
        "bounds": [bounds.west, bounds.south, bounds.east, bounds.north],
        "center": [center_lon, center_lat, minzoom],
        "minzoom": minzoom,
        "maxzoom": maxzoom,
        "tileSize": 256,
        **({"attribution": tileset.attribution} if tileset.attribution else {}),
    }


def _build_mbtiles_style(tileset: MBTilesTileset) -> dict[str, object]:
    source_name = "tileset"
    if tileset.source_type == "raster-dem":
        style = build_style(
            tileset.public_tile_url_template,
            source_name=source_name,
            style_name=tileset.name or tileset.filename,
            scheme="xyz",
            encoding="mapbox",
            tile_size=256,
        )
        if tileset.bounds is not None:
            style["sources"][source_name]["bounds"] = [
                tileset.bounds.west,
                tileset.bounds.south,
                tileset.bounds.east,
                tileset.bounds.north,
            ]
        if tileset.minzoom is not None:
            style["sources"][source_name]["minzoom"] = tileset.minzoom
        if tileset.maxzoom is not None:
            style["sources"][source_name]["maxzoom"] = tileset.maxzoom
        if tileset.attribution:
            style["sources"][source_name]["attribution"] = tileset.attribution
        style["glyphs"] = "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf"
        style["sources"]["osm-base"] = {
            "type": "raster",
            "tiles": [
                "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
            ],
            "tileSize": 256,
            "minzoom": 0,
            "maxzoom": 19,
            "attribution": "OpenStreetMap contributors",
        }
        if tileset.view is not None:
            style["center"] = [tileset.view.center_lon, tileset.view.center_lat]
            style["zoom"] = tileset.view.zoom
        style["layers"].append(
            {
                "id": "osm-base-layer",
                "type": "raster",
                "source": "osm-base",
            }
        )
        style["layers"].append(
            {
                "id": "terrain-hillshade",
                "type": "hillshade",
                "source": source_name,
                "paint": {
                    "hillshade-exaggeration": 1.0,
                    "hillshade-shadow-color": "rgba(0, 0, 0, 0.35)",
                    "hillshade-highlight-color": "rgba(255, 255, 255, 0.25)",
                    "hillshade-accent-color": "rgba(0, 0, 0, 0.12)",
                },
            }
        )
        style["terrain"] = {"source": source_name, "exaggeration": 1.0}
        return style

    style: dict[str, object] = {
        "version": 8,
        "name": tileset.name or tileset.filename,
        "sources": {
            source_name: {
                "type": "raster",
                "tiles": [tileset.public_tile_url_template],
                "tileSize": 256,
                **(
                    {
                        "bounds": [
                            tileset.bounds.west,
                            tileset.bounds.south,
                            tileset.bounds.east,
                            tileset.bounds.north,
                        ]
                    }
                    if tileset.bounds is not None
                    else {}
                ),
                **({"minzoom": tileset.minzoom} if tileset.minzoom is not None else {}),
                **({"maxzoom": tileset.maxzoom} if tileset.maxzoom is not None else {}),
                **({"attribution": tileset.attribution} if tileset.attribution else {}),
            }
        },
        "layers": [
            {
                "id": "tileset-raster",
                "type": "raster",
                "source": source_name,
            }
        ],
    }
    if tileset.view is not None:
        style["center"] = [tileset.view.center_lon, tileset.view.center_lat]
        style["zoom"] = tileset.view.zoom
    return style


def _build_mbtiles_mobile_style(tileset: MBTilesTileset) -> dict[str, object]:
    source_name = "tileset"
    style: dict[str, object] = {
        "version": 8,
        "name": f"{tileset.name or tileset.filename} (mobile)",
        "sources": {
            "osm-base": {
                "type": "raster",
                "tiles": [
                    "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
                    "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
                    "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
                ],
                "tileSize": 256,
                "minzoom": 0,
                "maxzoom": 19,
                "attribution": "OpenStreetMap contributors",
            },
            source_name: {
                "type": tileset.source_type,
                "tiles": [tileset.public_tile_url_template],
                "tileSize": 256,
                **(
                    {
                        "bounds": [
                            tileset.bounds.west,
                            tileset.bounds.south,
                            tileset.bounds.east,
                            tileset.bounds.north,
                        ]
                    }
                    if tileset.bounds is not None
                    else {}
                ),
                **({"minzoom": tileset.minzoom} if tileset.minzoom is not None else {}),
                **({"maxzoom": tileset.maxzoom} if tileset.maxzoom is not None else {}),
                **({"encoding": "mapbox"} if tileset.source_type == "raster-dem" else {}),
            },
        },
        "layers": [
            {
                "id": "osm-base-layer",
                "type": "raster",
                "source": "osm-base",
            },
        ],
    }
    if tileset.view is not None:
        style["center"] = [tileset.view.center_lon, tileset.view.center_lat]
        style["zoom"] = tileset.view.zoom
    if tileset.source_type == "raster-dem":
        style["layers"].append(
            {
                "id": "terrain-hillshade",
                "type": "hillshade",
                "source": source_name,
                "paint": {
                    "hillshade-exaggeration": 1.0,
                    "hillshade-shadow-color": "rgba(0, 0, 0, 0.35)",
                    "hillshade-highlight-color": "rgba(255, 255, 255, 0.25)",
                    "hillshade-accent-color": "rgba(0, 0, 0, 0.12)",
                },
            }
        )
    else:
        style["layers"].append(
            {
                "id": "tileset-raster",
                "type": "raster",
                "source": source_name,
            }
        )
    return style


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    storage = Storage(settings.storage_root)
    websocket_manager = WebSocketManager()
    jobs = JobManager(settings, storage, websocket_manager)

    app = FastAPI(title=settings.app_name)
    app.state.settings = settings
    app.state.storage = storage
    app.state.jobs = jobs
    app.state.websocket_manager = websocket_manager
    app.state.mbtiles_tilesets: dict[str, MBTilesTileset] = {}
    app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

    @app.get("/api/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/api/server-info")
    def server_info(request: Request) -> dict[str, object]:
        return _server_info(request).model_dump(mode="json")

    @app.get("/api/jobs")
    def list_jobs() -> list[dict[str, object]]:
        return [job.model_dump(mode="json") for job in jobs.list_jobs()]

    @app.get("/api/jobs/{job_id}")
    def get_job(job_id: str) -> dict[str, object]:
        try:
            return jobs.get_job(job_id).model_dump(mode="json")
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="Job not found") from exc

    @app.get("/api/jobs/{job_id}/logs")
    def get_logs(job_id: str) -> dict[str, list[str]]:
        try:
            return {"logs": jobs.get_job(job_id).logs}
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="Job not found") from exc

    @app.post("/api/jobs")
    async def create_job(
        request: Request,
        hgt_files: list[UploadFile] = File(...),
        base_mbtiles: UploadFile | None = File(default=None),
        bbox_mode: str = Form("auto"),
        west: float | None = Form(default=None),
        south: float | None = Form(default=None),
        east: float | None = Form(default=None),
        north: float | None = Form(default=None),
        minzoom: int = Form(8),
        maxzoom: int = Form(12),
        tile_size: int = Form(256),
        scheme: str = Form("xyz"),
        encoding: str = Form("mapbox"),
    ) -> dict[str, object]:
        bbox = None
        if bbox_mode == "manual":
            if None in {west, south, east, north}:
                raise HTTPException(status_code=422, detail="Manual bbox requires west/south/east/north")
            bbox = BBox(west=west, south=south, east=east, north=north)
        options = JobOptions(
            bbox_mode=bbox_mode,
            bbox=bbox,
            minzoom=minzoom,
            maxzoom=maxzoom,
            tile_size=tile_size,
            scheme=scheme,
            encoding=encoding,
        )
        job = jobs.create_job(options, has_base_mbtiles=base_mbtiles is not None)
        paths = storage.paths_for(job.id)
        for upload in hgt_files:
            await storage.save_upload(upload, paths.uploads / Path(upload.filename or "input.hgt").name)
        if base_mbtiles is not None:
            await storage.save_upload(base_mbtiles, paths.base_mbtiles)
        base_url = _public_base_url(request)
        jobs.start_job(job.id, base_url)
        return jobs.get_job(job.id).model_dump(mode="json")

    @app.websocket("/ws/jobs/{job_id}")
    async def job_websocket(websocket: WebSocket, job_id: str) -> None:
        try:
            job = jobs.get_job(job_id)
        except KeyError:
            await websocket.close(code=4404)
            return
        await websocket_manager.connect(job_id, websocket)
        await websocket.send_json({"type": "job", "job": job.model_dump(mode="json")})
        try:
            while True:
                await websocket.receive_text()
        except WebSocketDisconnect:
            websocket_manager.disconnect(job_id, websocket)

    @app.get("/api/jobs/{job_id}/downloads/{artifact_name}")
    def download_artifact(job_id: str, artifact_name: str) -> FileResponse:
        paths = storage.paths_for(job_id)
        mapping = {
            "terrain-rgb.mbtiles": paths.terrain_mbtiles,
            "tiles.json": paths.tilejson,
            "style.json": paths.stylejson,
        }
        artifact = mapping.get(artifact_name)
        if artifact is None or not artifact.exists():
            raise HTTPException(status_code=404, detail="Artifact not found")
        return FileResponse(artifact)

    @app.get("/api/jobs/{job_id}/terrain/{z}/{x}/{y}.png")
    def serve_terrain_tile(job_id: str, z: int, x: int, y: int) -> FileResponse:
        try:
            job = jobs.get_job(job_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="Job not found") from exc
        paths = storage.paths_for(job_id)
        xyz_y = y if job.options.scheme == "xyz" else ((1 << z) - 1) - y
        tile_path = paths.terrain_root / str(z) / str(x) / f"{xyz_y}.png"
        if not tile_path.exists():
            raise HTTPException(status_code=404, detail="Tile not found")
        return FileResponse(tile_path, media_type="image/png")

    @app.get("/api/jobs/{job_id}/base/{z}/{x}/{y}")
    def serve_base_tile(job_id: str, z: int, x: int, y: int) -> Response:
        paths = storage.paths_for(job_id)
        server = MBTilesServer(paths.base_mbtiles)
        tile = server.get_tile(z, x, y)
        if tile is None:
            raise HTTPException(status_code=404, detail="Tile not found")
        tile_data, media_type = tile
        return Response(tile_data, media_type=media_type)

    @app.get("/api/jobs/{job_id}/tilejson")
    def serve_tilejson(job_id: str) -> FileResponse:
        paths = storage.paths_for(job_id)
        if not paths.tilejson.exists():
            raise HTTPException(status_code=404, detail="TileJSON not found")
        return FileResponse(paths.tilejson, media_type="application/json")

    @app.get("/api/jobs/{job_id}/style")
    def serve_style(job_id: str) -> FileResponse:
        paths = storage.paths_for(job_id)
        if not paths.stylejson.exists():
            raise HTTPException(status_code=404, detail="Style not found")
        return FileResponse(paths.stylejson, media_type="application/json")

    @app.get("/api/mbtiles")
    def list_mbtiles_tilesets() -> list[dict[str, object]]:
        return [tileset.model_dump(mode="json") for tileset in app.state.mbtiles_tilesets.values()]

    @app.post("/api/mbtiles")
    async def upload_mbtiles_tileset(
        request: Request,
        mbtiles: UploadFile = File(...),
        source_type: str = Form("auto"),
    ) -> dict[str, object]:
        if not (mbtiles.filename or "").lower().endswith(".mbtiles"):
            raise HTTPException(status_code=422, detail="Upload a .mbtiles file")
        if source_type not in {"auto", "raster", "raster-dem"}:
            raise HTTPException(status_code=422, detail="source_type must be auto, raster, or raster-dem")
        tileset_id = uuid.uuid4().hex
        paths = storage.tileset_paths_for(tileset_id)
        await storage.save_upload(mbtiles, paths.mbtiles)
        server = MBTilesServer(paths.mbtiles)
        metadata = server.get_metadata()
        tile_format = server.get_format()
        resolved_source_type = _guess_mbtiles_source_type(mbtiles.filename or "", metadata) if source_type == "auto" else source_type
        tileset = _build_mbtiles_tileset_payload(
            request=request,
            tileset_id=tileset_id,
            filename=Path(mbtiles.filename or "tiles.mbtiles").name,
            created_at=datetime.now(timezone.utc),
            server=server,
            source_type=resolved_source_type,
        )
        app.state.mbtiles_tilesets[tileset_id] = tileset
        return tileset.model_dump(mode="json")

    @app.get("/api/mbtiles/{tileset_id}")
    def get_mbtiles_tileset(tileset_id: str) -> dict[str, object]:
        tileset = app.state.mbtiles_tilesets.get(tileset_id)
        if tileset is None:
            raise HTTPException(status_code=404, detail="MBTiles tileset not found")
        return tileset.model_dump(mode="json")

    @app.get("/api/mbtiles/{tileset_id}/metadata")
    def get_mbtiles_tileset_metadata(tileset_id: str) -> dict[str, str]:
        tileset = app.state.mbtiles_tilesets.get(tileset_id)
        if tileset is None:
            raise HTTPException(status_code=404, detail="MBTiles tileset not found")
        return MBTilesServer(storage.tileset_paths_for(tileset_id).mbtiles).get_metadata()

    @app.get("/api/mbtiles/{tileset_id}/tilejson")
    def get_mbtiles_tileset_tilejson(tileset_id: str) -> Response:
        tileset = app.state.mbtiles_tilesets.get(tileset_id)
        if tileset is None:
            raise HTTPException(status_code=404, detail="MBTiles tileset not found")
        return Response(json.dumps(_build_mbtiles_tilejson(tileset), indent=2) + "\n", media_type="application/json")

    @app.get("/api/mbtiles/{tileset_id}/style")
    def get_mbtiles_tileset_style(tileset_id: str) -> Response:
        tileset = app.state.mbtiles_tilesets.get(tileset_id)
        if tileset is None:
            raise HTTPException(status_code=404, detail="MBTiles tileset not found")
        return Response(json.dumps(_build_mbtiles_style(tileset), indent=2) + "\n", media_type="application/json")

    @app.get("/api/mbtiles/{tileset_id}/style-mobile")
    def get_mbtiles_tileset_mobile_style(tileset_id: str) -> Response:
        tileset = app.state.mbtiles_tilesets.get(tileset_id)
        if tileset is None:
            raise HTTPException(status_code=404, detail="MBTiles tileset not found")
        return Response(json.dumps(_build_mbtiles_mobile_style(tileset), indent=2) + "\n", media_type="application/json")

    def _serve_mbtiles_tile(tileset_id: str, z: int, x: int, y: int) -> Response:
        tileset = app.state.mbtiles_tilesets.get(tileset_id)
        if tileset is None:
            raise HTTPException(status_code=404, detail="MBTiles tileset not found")
        server = MBTilesServer(storage.tileset_paths_for(tileset_id).mbtiles)
        tile = server.get_tile(z, x, y)
        if tile is None:
            raise HTTPException(status_code=404, detail="Tile not found")
        tile_data, media_type = tile
        return Response(tile_data, media_type=media_type)

    @app.get("/api/mbtiles/{tileset_id}/{z}/{x}/{y}.{ext}")
    def serve_mbtiles_tile_with_extension(tileset_id: str, z: int, x: int, y: int, ext: str) -> Response:
        return _serve_mbtiles_tile(tileset_id, z, x, y)

    @app.get("/api/mbtiles/{tileset_id}/{z}/{x}/{y}")
    def serve_mbtiles_tile(tileset_id: str, z: int, x: int, y: int) -> Response:
        return _serve_mbtiles_tile(tileset_id, z, x, y)

    if settings.frontend_dist.exists():
        app.mount("/", StaticFiles(directory=settings.frontend_dist, html=True), name="frontend")

    return app


app = create_app()
