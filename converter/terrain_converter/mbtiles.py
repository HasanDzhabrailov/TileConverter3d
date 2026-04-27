"""Write terrain tiles into MBTiles format."""

from __future__ import annotations

import sqlite3
from pathlib import Path


MBTILES_SCHEMA = """
CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT);
CREATE TABLE IF NOT EXISTS tiles (
    zoom_level INTEGER,
    tile_column INTEGER,
    tile_row INTEGER,
    tile_data BLOB
);
CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row);
CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name);
"""


def xyz_to_tms_row(zoom: int, y: int) -> int:
    return ((1 << zoom) - 1) - y


class MBTilesWriter:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(self.path)
        self.connection.executescript(MBTILES_SCHEMA)

    def write_metadata(self, metadata: dict[str, str]) -> None:
        rows = sorted(metadata.items())
        self.connection.executemany(
            "INSERT OR REPLACE INTO metadata(name, value) VALUES(?, ?)",
            rows,
        )
        self.connection.commit()

    def write_tile(self, zoom: int, x: int, y: int, tile_data: bytes) -> None:
        self.connection.execute(
            "INSERT OR REPLACE INTO tiles(zoom_level, tile_column, tile_row, tile_data) VALUES(?, ?, ?, ?)",
            (zoom, x, xyz_to_tms_row(zoom, y), tile_data),
        )

    def close(self) -> None:
        self.connection.commit()
        self.connection.close()

    def __enter__(self) -> "MBTilesWriter":
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        self.close()
