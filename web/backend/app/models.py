from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import BaseModel, Field


class JobStatus(str, Enum):
    pending = "pending"
    running = "running"
    completed = "completed"
    failed = "failed"


class BBox(BaseModel):
    west: float
    south: float
    east: float
    north: float


class MapView(BaseModel):
    center_lon: float
    center_lat: float
    zoom: int


class JobOptions(BaseModel):
    bbox_mode: str = "auto"
    bbox: BBox | None = None
    minzoom: int = 8
    maxzoom: int = 12
    tile_size: int = 256
    scheme: str = "xyz"
    encoding: str = "mapbox"


class JobArtifacts(BaseModel):
    terrain_mbtiles: str | None = None
    tilejson: str | None = None
    stylejson: str | None = None
    terrain_tile_url_template: str | None = None
    public_terrain_tile_url_template: str | None = None
    public_tilejson: str | None = None
    public_stylejson: str | None = None


class JobResult(BaseModel):
    bounds: BBox | None = None
    tile_count: int | None = None


class JobSummary(BaseModel):
    id: str
    status: JobStatus
    created_at: datetime
    updated_at: datetime
    options: JobOptions
    has_base_mbtiles: bool = False
    artifacts: JobArtifacts = Field(default_factory=JobArtifacts)
    result: JobResult = Field(default_factory=JobResult)
    error: str | None = None


class JobDetail(JobSummary):
    logs: list[str] = Field(default_factory=list)


class MBTilesTileset(BaseModel):
    id: str
    filename: str
    created_at: datetime
    tile_url_template: str
    public_tile_url_template: str
    tilejson_url: str | None = None
    public_tilejson_url: str | None = None
    style_url: str | None = None
    public_style_url: str | None = None
    mobile_style_url: str | None = None
    public_mobile_style_url: str | None = None
    name: str | None = None
    attribution: str | None = None
    source_type: str = "raster"
    tile_format: str = "png"
    minzoom: int | None = None
    maxzoom: int | None = None
    bounds: BBox | None = None
    view: MapView | None = None


class ServerAddress(BaseModel):
    id: str
    label: str
    host: str
    base_url: str
    description: str


class ServerInfo(BaseModel):
    addresses: list[ServerAddress] = Field(default_factory=list)
