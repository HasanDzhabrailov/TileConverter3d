"""Read and sample HGT/SRTM elevation tiles."""

from __future__ import annotations

import math
import mmap
import re
import struct
from dataclasses import dataclass
from pathlib import Path

import numpy as np

HGT_NAME_RE = re.compile(r"^(?P<lat_ns>[NS])(?P<lat>\d{2})(?P<lon_ew>[EW])(?P<lon>\d{3})\.hgt$", re.IGNORECASE)
VOID_VALUE = -32768
SUPPORTED_GRID_SIZES = {
    1201 * 1201 * 2: 1201,
    3601 * 3601 * 2: 3601,
}
SAMPLE_STRUCT = struct.Struct(">h")


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
    samples: mmap.mmap
    grid: np.ndarray
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

    def _sample_value(self, index: int) -> int:
        return SAMPLE_STRUCT.unpack_from(self.samples, index * 2)[0]

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

        weight00 = (1.0 - fx) * (1.0 - fy)
        weight10 = fx * (1.0 - fy)
        weight01 = (1.0 - fx) * fy
        weight11 = fx * fy

        value00 = self._sample_value(self._index(row0, col0))
        value10 = self._sample_value(self._index(row0, col1))
        value01 = self._sample_value(self._index(row1, col0))
        value11 = self._sample_value(self._index(row1, col1))

        weighted = 0.0
        total_weight = 0.0
        fallback = None

        for value, weight in (
            (value00, weight00),
            (value10, weight10),
            (value01, weight01),
            (value11, weight11),
        ):
            if value == self.void_value:
                continue
            if fallback is None:
                fallback = float(value)
            weighted += float(value) * weight
            total_weight += weight
        if total_weight > 0.0:
            return weighted / total_weight
        return fallback

    def sample_bilinear_array(self, lon: np.ndarray, lat: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        lon = np.minimum(np.maximum(lon, self.west), np.nextafter(self.east, self.west))
        lat = np.minimum(np.maximum(lat, self.south), np.nextafter(self.north, self.south))

        col_f = (lon - self.west) * self.resolution
        row_f = (self.north - lat) * self.resolution

        col0 = np.floor(col_f).astype(np.intp, copy=False)
        row0 = np.floor(row_f).astype(np.intp, copy=False)
        col1 = np.minimum(col0 + 1, self.size - 1)
        row1 = np.minimum(row0 + 1, self.size - 1)

        fx = col_f - col0
        fy = row_f - row0

        weight00 = (1.0 - fx) * (1.0 - fy)
        weight10 = fx * (1.0 - fy)
        weight01 = (1.0 - fx) * fy
        weight11 = fx * fy

        grid = self.grid
        value00 = grid[row0, col0].astype(np.float64, copy=False)
        value10 = grid[row0, col1].astype(np.float64, copy=False)
        value01 = grid[row1, col0].astype(np.float64, copy=False)
        value11 = grid[row1, col1].astype(np.float64, copy=False)

        valid00 = value00 != self.void_value
        valid10 = value10 != self.void_value
        valid01 = value01 != self.void_value
        valid11 = value11 != self.void_value

        weighted = np.zeros(lon.shape, dtype=np.float64)
        total_weight = np.zeros(lon.shape, dtype=np.float64)
        weighted += np.where(valid00, value00 * weight00, 0.0)
        weighted += np.where(valid10, value10 * weight10, 0.0)
        weighted += np.where(valid01, value01 * weight01, 0.0)
        weighted += np.where(valid11, value11 * weight11, 0.0)
        total_weight += np.where(valid00, weight00, 0.0)
        total_weight += np.where(valid10, weight10, 0.0)
        total_weight += np.where(valid01, weight01, 0.0)
        total_weight += np.where(valid11, weight11, 0.0)

        fallback = np.where(
            valid00,
            value00,
            np.where(valid10, value10, np.where(valid01, value01, np.where(valid11, value11, np.nan))),
        )
        values = np.full(lon.shape, np.nan, dtype=np.float64)
        np.divide(weighted, total_weight, out=values, where=total_weight > 0.0)
        values = np.where(total_weight > 0.0, values, fallback)
        valid = ~np.isnan(values)
        return values, valid


class HGTCollection:
    def __init__(self, tiles: list[HGTTile]):
        if not tiles:
            raise ValueError("at least one HGT tile is required")
        self.tiles = tiles
        self._tile_map = {(tile.south, tile.west): tile for tile in tiles}
        west = min(tile.west for tile in self.tiles)
        south = min(tile.south for tile in self.tiles)
        east = max(tile.east for tile in self.tiles)
        north = max(tile.north for tile in self.tiles)
        self._bounds = (float(west), float(south), float(east), float(north))

    @property
    def bounds(self) -> tuple[float, float, float, float]:
        return self._bounds

    def sample(self, lon: float, lat: float) -> float | None:
        west, south, east, north = self.bounds
        if not (west <= lon < east and south <= lat < north):
            return None
        tile_key = (math.floor(lat), math.floor(lon))
        tile = self._tile_map.get(tile_key)
        if tile is None:
            return None
        return tile.sample_bilinear(lon, lat)

    def sample_grid(self, lon: np.ndarray, lat: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        if lon.shape != lat.shape:
            raise ValueError("lon and lat grids must have the same shape")

        west, south, east, north = self.bounds
        values = np.full(lon.shape, np.nan, dtype=np.float64)
        valid = np.zeros(lon.shape, dtype=bool)
        in_bounds = (west <= lon) & (lon < east) & (south <= lat) & (lat < north)
        if not np.any(in_bounds):
            return values, valid

        min_lon = float(np.min(lon[in_bounds]))
        max_lon = float(np.max(lon[in_bounds]))
        min_lat = float(np.min(lat[in_bounds]))
        max_lat = float(np.max(lat[in_bounds]))
        west_start = math.floor(min_lon)
        west_end = math.floor(math.nextafter(max_lon, -math.inf))
        south_start = math.floor(min_lat)
        south_end = math.floor(math.nextafter(max_lat, -math.inf))

        for tile_south in range(south_start, south_end + 1):
            for tile_west in range(west_start, west_end + 1):
                tile = self._tile_map.get((tile_south, tile_west))
                if tile is None:
                    continue
                tile_mask = in_bounds & (tile.west <= lon) & (lon < tile.east) & (tile.south <= lat) & (lat < tile.north)
                if not np.any(tile_mask):
                    continue
                tile_values, tile_valid = tile.sample_bilinear_array(lon[tile_mask], lat[tile_mask])
                if not np.any(tile_valid):
                    continue
                masked_values = values[tile_mask]
                masked_values[tile_valid] = tile_values[tile_valid]
                values[tile_mask] = masked_values
                masked_valid = valid[tile_mask]
                masked_valid[tile_valid] = True
                valid[tile_mask] = masked_valid

        return values, valid


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
    with path.open("rb") as file_obj:
        samples = mmap.mmap(file_obj.fileno(), length=0, access=mmap.ACCESS_READ)
    grid = np.frombuffer(samples, dtype=">i2").reshape((size, size))
    return HGTTile(
        path=path,
        south=coordinate.lat,
        west=coordinate.lon,
        size=size,
        samples=samples,
        grid=grid,
    )


def load_hgt_collection(paths: list[str | Path]) -> HGTCollection:
    tiles = [read_hgt(path) for path in sorted(Path(path) for path in paths)]
    return HGTCollection(tiles)
