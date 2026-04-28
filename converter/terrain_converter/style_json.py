"""Generate a MapLibre style that references the terrain DEM source."""

from __future__ import annotations

import json
from pathlib import Path


def build_style(
    tiles_url: str,
    *,
    source_name: str = "terrain-dem",
    style_name: str = "Terrain DEM Style",
    scheme: str = "xyz",
    encoding: str = "mapbox",
    tile_size: int = 256,
) -> dict[str, object]:
    return {
        "version": 8,
        "name": style_name,
        "sources": {
            source_name: {
                "type": "raster-dem",
                "tiles": [tiles_url],
                "encoding": encoding,
                "tileSize": tile_size,
                "scheme": scheme,
            }
        },
        "terrain": {"source": source_name, "exaggeration": 1.0},
        "layers": [],
    }


def write_style(path: str | Path, style: dict[str, object]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(style, indent=2) + "\n", encoding="utf-8")
