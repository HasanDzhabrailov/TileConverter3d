"""Generate TileJSON metadata for terrain tiles."""

from __future__ import annotations

import json
from pathlib import Path

from .bbox import Bounds


def build_tilejson(bounds: Bounds, min_zoom: int, max_zoom: int, tiles_url: str) -> dict[str, object]:
    center_lon, center_lat = bounds.center()
    return {
        "tilejson": "3.0.0",
        "name": "terrain-dem",
        "type": "raster-dem",
        "scheme": "xyz",
        "encoding": "mapbox",
        "format": "png",
        "tileSize": 256,
        "tiles": [tiles_url],
        "bounds": [bounds.west, bounds.south, bounds.east, bounds.north],
        "center": [center_lon, center_lat, min_zoom],
        "minzoom": min_zoom,
        "maxzoom": max_zoom,
    }


def write_tilejson(path: str | Path, tilejson: dict[str, object]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(tilejson, indent=2) + "\n", encoding="utf-8")
