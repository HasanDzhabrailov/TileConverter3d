from __future__ import annotations

import json
from pathlib import Path

from terrain_converter.bbox import Bounds
from terrain_converter.style_json import build_style
from terrain_converter.tilejson import build_tilejson

from .models import BBox, JobOptions


def terrain_tile_url(job_id: str) -> str:
    return f"/api/jobs/{job_id}/terrain/{{z}}/{{x}}/{{y}}.png"


def base_tile_url(job_id: str) -> str:
    return f"/api/jobs/{job_id}/base/{{z}}/{{x}}/{{y}}"


def write_job_documents(
    *,
    job_id: str,
    options: JobOptions,
    bounds: BBox,
    tilejson_path: Path,
    stylejson_path: Path,
    has_base_mbtiles: bool,
) -> None:
    terrain_url = terrain_tile_url(job_id)
    tilejson = build_tilejson(
        Bounds(**bounds.model_dump()),
        min_zoom=options.minzoom,
        max_zoom=options.maxzoom,
        tiles_url=terrain_url,
        name="terrain-dem",
        scheme=options.scheme,
        encoding=options.encoding,
        tile_size=options.tile_size,
    )
    style = build_style(
        terrain_url,
        source_name="terrain-dem",
        scheme=options.scheme,
        encoding=options.encoding,
        tile_size=options.tile_size,
    )
    style["glyphs"] = "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf"
    style["center"] = tilejson["center"]
    style["zoom"] = options.minzoom
    style["layers"].append(
        {
            "id": "background",
            "type": "background",
            "paint": {"background-color": "#0f172a"},
        }
    )
    if has_base_mbtiles:
        style["sources"]["base-map"] = {
            "type": "raster",
            "tiles": [base_tile_url(job_id)],
            "tileSize": 256,
        }
        style["layers"].append(
            {
                "id": "base-map",
                "type": "raster",
                "source": "base-map",
            }
        )
    style["layers"].append(
        {
            "id": "terrain-hillshade",
            "type": "hillshade",
            "source": "terrain-dem",
        }
    )
    tilejson_path.write_text(json.dumps(tilejson, indent=2) + "\n", encoding="utf-8")
    stylejson_path.write_text(json.dumps(style, indent=2) + "\n", encoding="utf-8")
