from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    app_name: str = "terrain-converter-web"
    converter_bin: str = "terrain-converter"
    host: str = "0.0.0.0"
    port: int = 8080
    storage_root: Path = Path(__file__).resolve().parents[2] / "data"
    frontend_dist: Path = Path(__file__).resolve().parents[2] / "frontend" / "dist"

    @classmethod
    def from_env(cls) -> "Settings":
        storage_root = Path(os.getenv("TERRAIN_WEB_STORAGE_ROOT", cls.storage_root))
        frontend_dist = Path(os.getenv("TERRAIN_WEB_FRONTEND_DIST", cls.frontend_dist))
        return cls(
            app_name=os.getenv("TERRAIN_WEB_APP_NAME", cls.app_name),
            converter_bin=os.getenv("TERRAIN_WEB_CONVERTER_BIN", cls.converter_bin),
            host=os.getenv("TERRAIN_WEB_HOST", cls.host),
            port=int(os.getenv("TERRAIN_WEB_PORT", str(cls.port))),
            storage_root=storage_root,
            frontend_dist=frontend_dist,
        )
