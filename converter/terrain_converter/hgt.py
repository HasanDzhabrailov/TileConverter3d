"""Read and sample HGT/SRTM elevation tiles."""

from __future__ import annotations

import math
import re
import sys
from array import array
from dataclasses import dataclass
from pathlib import Path

HGT_NAME_RE = re.compile(r"^(?P<lat_ns>[NS])(?P<lat>\d{2})(?P<lon_ew>[EW])(?P<lon>\d{3})\.hgt$", re.IGNORECASE)
VOID_VALUE = -32768
SUPPORTED_GRID_SIZES = {
    1201 * 1201 * 2: 1201,
    3601 * 3601 * 2: 3601,
}


@dataclass(frozen=True)
class HGTCoordinate:
    lat: int
    lon: int


@dataclass
class HGTTile:
    path: Path
    south: int
    west: int
    size: int
    samples: array
    void_value: int = VOID_VALUE

    @property
    def north(self) -> int:
        return self.south + 1

    @property
    def east(self) -> int:
        return self.west + 1

    @property
    def resolution(self) -> int:
        return self.size - 1

    @property
    def extent(self) -> tuple[float, float, float, float]:
        return (float(self.west), float(self.south), float(self.east), float(self.north))

    def _index(self, row: int, col: int) -> int:
        return (row * self.size) + col

    def covers(self, lon: float, lat: float) -> bool:
        return self.west <= lon < self.east and self.south <= lat < self.north

    def sample_bilinear(self, lon: float, lat: float) -> float | None:
        if not self.covers(lon, lat):
            return None

        lon = min(max(lon, self.west), math.nextafter(self.east, self.west))
        lat = min(max(lat, self.south), math.nextafter(self.north, self.south))

        col_f = (lon - self.west) * self.resolution
        row_f = (self.north - lat) * self.resolution

        col0 = int(math.floor(col_f))
        row0 = int(math.floor(row_f))
        col1 = min(col0 + 1, self.size - 1)
        row1 = min(row0 + 1, self.size - 1)

        fx = col_f - col0
        fy = row_f - row0

        neighbors = [
            (self.samples[self._index(row0, col0)], (1.0 - fx) * (1.0 - fy)),
            (self.samples[self._index(row0, col1)], fx * (1.0 - fy)),
            (self.samples[self._index(row1, col0)], (1.0 - fx) * fy),
            (self.samples[self._index(row1, col1)], fx * fy),
        ]
        weighted = 0.0
        total_weight = 0.0
        fallback = None
        for value, weight in neighbors:
            if value == self.void_value:
                continue
            if fallback is None:
                fallback = float(value)
            weighted += float(value) * weight
            total_weight += weight
        if total_weight > 0.0:
            return weighted / total_weight
        return fallback


class HGTCollection:
    def __init__(self, tiles: list[HGTTile]):
        if not tiles:
            raise ValueError("at least one HGT tile is required")
        self.tiles = tiles
        self._tile_map = {(tile.south, tile.west): tile for tile in tiles}

    @property
    def bounds(self) -> tuple[float, float, float, float]:
        west = min(tile.west for tile in self.tiles)
        south = min(tile.south for tile in self.tiles)
        east = max(tile.east for tile in self.tiles)
        north = max(tile.north for tile in self.tiles)
        return float(west), float(south), float(east), float(north)

    def sample(self, lon: float, lat: float) -> float | None:
        west, south, east, north = self.bounds
        if not (west <= lon < east and south <= lat < north):
            return None
        tile_key = (math.floor(lat), math.floor(lon))
        tile = self._tile_map.get(tile_key)
        if tile is None:
            return None
        return tile.sample_bilinear(lon, lat)


def parse_hgt_coordinate(path: str | Path) -> HGTCoordinate:
    match = HGT_NAME_RE.match(Path(path).name)
    if not match:
        raise ValueError(f"invalid HGT filename: {Path(path).name}")
    lat = int(match.group("lat"))
    lon = int(match.group("lon"))
    if match.group("lat_ns").upper() == "S":
        lat *= -1
    if match.group("lon_ew").upper() == "W":
        lon *= -1
    return HGTCoordinate(lat=lat, lon=lon)


def infer_hgt_size(path: str | Path) -> int:
    size_bytes = Path(path).stat().st_size
    size = SUPPORTED_GRID_SIZES.get(size_bytes)
    if size is None:
        raise ValueError(f"unsupported HGT file size: {size_bytes} bytes")
    return size


def read_hgt(path: str | Path) -> HGTTile:
    path = Path(path)
    coordinate = parse_hgt_coordinate(path)
    size = infer_hgt_size(path)
    data = path.read_bytes()
    samples = array("h")
    samples.frombytes(data)
    if samples.itemsize != 2:
        raise ValueError("unexpected HGT sample width")
    if sys.byteorder != "big":
        samples.byteswap()
    return HGTTile(
        path=path,
        south=coordinate.lat,
        west=coordinate.lon,
        size=size,
        samples=samples,
    )


def load_hgt_collection(paths: list[str | Path]) -> HGTCollection:
    tiles = [read_hgt(path) for path in sorted(Path(path) for path in paths)]
    return HGTCollection(tiles)
