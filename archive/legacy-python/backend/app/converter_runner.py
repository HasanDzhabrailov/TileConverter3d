from __future__ import annotations

import json
import subprocess
import zipfile
from pathlib import Path
from typing import Callable

from .config import Settings
from .models import BBox, JobOptions
from .storage import JobPaths
from .tilejson import write_job_documents


def _extract_input(source: Path, target_dir: Path) -> list[Path]:
    if source.suffix.lower() == ".zip":
        extracted: list[Path] = []
        with zipfile.ZipFile(source) as archive:
            for member in archive.infolist():
                if member.is_dir() or not member.filename.lower().endswith(".hgt"):
                    continue
                destination = target_dir / Path(member.filename).name
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(member) as input_stream, destination.open("wb") as output_stream:
                    output_stream.write(input_stream.read())
                extracted.append(destination)
        return extracted
    if source.suffix.lower() == ".hgt":
        return [source]
    return []


def prepare_hgt_inputs(paths: JobPaths) -> list[Path]:
    inputs: list[Path] = []
    for upload in sorted(paths.uploads.iterdir()):
        if upload.name == "base.mbtiles":
            continue
        inputs.extend(_extract_input(upload, paths.inputs))
    if not inputs:
        raise ValueError("No HGT files were provided")
    return inputs


def _bbox_args(options: JobOptions) -> list[str]:
    if options.bbox_mode != "manual" or options.bbox is None:
        return []
    return [
        "--bbox",
        str(options.bbox.west),
        str(options.bbox.south),
        str(options.bbox.east),
        str(options.bbox.north),
    ]


def run_conversion(
    *,
    settings: Settings,
    job_id: str,
    options: JobOptions,
    paths: JobPaths,
    base_url: str,
    log: Callable[[str], None],
) -> dict[str, object]:
    input_paths = prepare_hgt_inputs(paths)
    command = [
        settings.converter_bin,
        *[str(path) for path in input_paths],
        "--output-mbtiles",
        str(paths.terrain_mbtiles),
        "--tile-root",
        str(paths.terrain_root),
        "--tilejson",
        str(paths.tilejson),
        "--style-json",
        str(paths.stylejson),
        "--tiles-url",
        f"{base_url}/api/jobs/{job_id}/terrain/{{z}}/{{x}}/{{y}}.png",
        "--minzoom",
        str(options.minzoom),
        "--maxzoom",
        str(options.maxzoom),
        "--tile-size",
        str(options.tile_size),
        "--scheme",
        options.scheme,
        "--encoding",
        options.encoding,
        *_bbox_args(options),
    ]
    log("$ " + " ".join(command))
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    assert process.stdout is not None
    for line in process.stdout:
        log(line.rstrip())
    return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(f"terrain-converter failed with exit code {return_code}")

    tilejson_data = json.loads(paths.tilejson.read_text(encoding="utf-8"))
    bounds = BBox(
        west=tilejson_data["bounds"][0],
        south=tilejson_data["bounds"][1],
        east=tilejson_data["bounds"][2],
        north=tilejson_data["bounds"][3],
    )
    write_job_documents(
        job_id=job_id,
        options=options,
        bounds=bounds,
        tilejson_path=paths.tilejson,
        stylejson_path=paths.stylejson,
        has_base_mbtiles=paths.base_mbtiles.exists(),
    )
    return {
        "bounds": bounds.model_dump(),
        "tile_count": _count_rendered_tiles(paths.terrain_root),
    }


def _count_rendered_tiles(terrain_root: Path) -> int:
    return sum(1 for path in terrain_root.rglob("*.png") if path.is_file())
