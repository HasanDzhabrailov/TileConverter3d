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
    _BATCH_SIZE = 256

    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(self.path)
        self.connection.execute("PRAGMA journal_mode=MEMORY")
        self.connection.execute("PRAGMA synchronous=NORMAL")
        self.connection.execute("PRAGMA temp_store=MEMORY")
        self.connection.execute("PRAGMA cache_size=-65536")
        self.connection.executescript(MBTILES_SCHEMA)
        self._tile_batch: list[tuple[int, int, int, bytes]] = []

    def write_metadata(self, metadata: dict[str, str]) -> None:
        rows = sorted(metadata.items())
        self.connection.executemany(
            "INSERT OR REPLACE INTO metadata(name, value) VALUES(?, ?)",
            rows,
        )
        self.connection.commit()

    def write_tile(self, zoom: int, x: int, y: int, tile_data: bytes) -> None:
        self._tile_batch.append((zoom, x, xyz_to_tms_row(zoom, y), tile_data))
        if len(self._tile_batch) >= self._BATCH_SIZE:
            self._flush_tiles()

    def _flush_tiles(self) -> None:
        if not self._tile_batch:
            return
        self.connection.executemany(
            "INSERT OR REPLACE INTO tiles(zoom_level, tile_column, tile_row, tile_data) VALUES(?, ?, ?, ?)",
            self._tile_batch,
        )
        self._tile_batch.clear()

    def close(self) -> None:
        self._flush_tiles()
        self.connection.commit()
        self.connection.close()

    def __enter__(self) -> "MBTilesWriter":
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        self.close()
