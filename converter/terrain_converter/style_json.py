"""Generate a MapLibre style that references the terrain DEM source."""

from __future__ import annotations

import json
from pathlib import Path


def build_style(tiles_url: str) -> dict[str, object]:
    return {
        "version": 8,
        "name": "Terrain DEM Style",
        "sources": {
            "terrain-dem": {
                "type": "raster-dem",
                "tiles": [tiles_url],
                "encoding": "mapbox",
                "tileSize": 256,
                "scheme": "xyz",
            }
        },
        "terrain": {"source": "terrain-dem", "exaggeration": 1.0},
        "layers": [],
    }


def write_style(path: str | Path, style: dict[str, object]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(style, indent=2) + "\n", encoding="utf-8")
