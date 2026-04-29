from __future__ import annotations

import shutil
from dataclasses import dataclass
from pathlib import Path

from fastapi import UploadFile


@dataclass(frozen=True)
class JobPaths:
    root: Path
    uploads: Path
    inputs: Path
    outputs: Path
    terrain_root: Path
    terrain_mbtiles: Path
    tilejson: Path
    stylejson: Path
    base_mbtiles: Path


@dataclass(frozen=True)
class TilesetPaths:
    root: Path
    mbtiles: Path


class Storage:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.jobs_root = root / "jobs"
        self.tilesets_root = root / "tilesets"
        self.jobs_root.mkdir(parents=True, exist_ok=True)
        self.tilesets_root.mkdir(parents=True, exist_ok=True)

    def paths_for(self, job_id: str) -> JobPaths:
        root = self.jobs_root / job_id
        uploads = root / "uploads"
        inputs = root / "inputs"
        outputs = root / "outputs"
        terrain_root = outputs / "terrain"
        paths = JobPaths(
            root=root,
            uploads=uploads,
            inputs=inputs,
            outputs=outputs,
            terrain_root=terrain_root,
            terrain_mbtiles=outputs / "terrain-rgb.mbtiles",
            tilejson=outputs / "tiles.json",
            stylejson=outputs / "style.json",
            base_mbtiles=uploads / "base.mbtiles",
        )
        for path in [root, uploads, inputs, outputs, terrain_root]:
            path.mkdir(parents=True, exist_ok=True)
        return paths

    def tileset_paths_for(self, tileset_id: str) -> TilesetPaths:
        root = self.tilesets_root / tileset_id
        root.mkdir(parents=True, exist_ok=True)
        return TilesetPaths(root=root, mbtiles=root / "tiles.mbtiles")

    async def save_upload(self, upload: UploadFile, destination: Path) -> Path:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("wb") as output:
            while chunk := await upload.read(1024 * 1024):
                output.write(chunk)
        await upload.close()
        return destination

    def clear_inputs(self, paths: JobPaths) -> None:
        if paths.inputs.exists():
            shutil.rmtree(paths.inputs)
        paths.inputs.mkdir(parents=True, exist_ok=True)
