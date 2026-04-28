from __future__ import annotations

import sqlite3
from pathlib import Path

from terrain_converter.mbtiles import xyz_to_tms_row

from .models import BBox, MapView


MEDIA_TYPES = {
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "webp": "image/webp",
    "pbf": "application/x-protobuf",
}


def _sniff_media_type(tile_data: bytes) -> str:
    if tile_data.startswith(b"\x89PNG"):
        return "image/png"
    if tile_data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if tile_data.startswith(b"RIFF") and tile_data[8:12] == b"WEBP":
        return "image/webp"
    return "application/octet-stream"


class MBTilesServer:
    def __init__(self, path: Path) -> None:
        self.path = path

    def exists(self) -> bool:
        return self.path.exists()

    def get_metadata(self) -> dict[str, str]:
        if not self.path.exists():
            return {}
        connection = sqlite3.connect(self.path)
        try:
            rows = connection.execute("SELECT name, value FROM metadata").fetchall()
        finally:
            connection.close()
        return {name: value for name, value in rows}

    def get_format(self) -> str:
        return self.get_metadata().get("format", "png").lower()

    def get_name(self) -> str | None:
        return self.get_metadata().get("name")

    def get_attribution(self) -> str | None:
        return self.get_metadata().get("attribution")

    def get_minzoom(self) -> int | None:
        value = self.get_metadata().get("minzoom")
        if value is None:
            return None
        try:
            return int(float(value))
        except ValueError:
            return None

    def get_maxzoom(self) -> int | None:
        value = self.get_metadata().get("maxzoom")
        if value is None:
            return None
        try:
            return int(float(value))
        except ValueError:
            return None

    def get_bounds(self) -> BBox | None:
        value = self.get_metadata().get("bounds")
        if not value:
            return None
        try:
            west, south, east, north = [float(item) for item in value.split(",", 3)]
        except ValueError:
            return None
        return BBox(west=west, south=south, east=east, north=north)

    def get_view(self) -> MapView | None:
        metadata = self.get_metadata()
        center = metadata.get("center")
        if center:
            try:
                center_lon, center_lat, zoom = center.split(",", 2)
                return MapView(center_lon=float(center_lon), center_lat=float(center_lat), zoom=int(float(zoom)))
            except ValueError:
                pass

        bounds = self.get_bounds()
        if bounds is None:
            return None
        minzoom = metadata.get("minzoom", "0")
        try:
            zoom = int(float(minzoom))
        except ValueError:
            zoom = 0
        return MapView(
            center_lon=(bounds.west + bounds.east) / 2,
            center_lat=(bounds.south + bounds.north) / 2,
            zoom=zoom,
        )

    def get_tile(self, z: int, x: int, y: int) -> tuple[bytes, str] | None:
        if not self.path.exists():
            return None
        connection = sqlite3.connect(self.path)
        try:
            fmt_row = connection.execute("SELECT value FROM metadata WHERE name='format'").fetchone()
            row = connection.execute(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                (z, x, xyz_to_tms_row(z, y)),
            ).fetchone()
        finally:
            connection.close()
        if row is None:
            return None
        tile_data = row[0]
        media_type = MEDIA_TYPES.get((fmt_row[0] if fmt_row else "").lower(), _sniff_media_type(tile_data))
        return tile_data, media_type
