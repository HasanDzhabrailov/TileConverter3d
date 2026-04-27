"""Bounds and XYZ tile helpers."""

from __future__ import annotations

import math
from dataclasses import dataclass

MAX_MERCATOR_LAT = 85.05112878


@dataclass(frozen=True)
class Bounds:
    west: float
    south: float
    east: float
    north: float

    def clamped(self) -> "Bounds":
        return Bounds(
            west=max(-180.0, self.west),
            south=max(-MAX_MERCATOR_LAT, self.south),
            east=min(180.0, self.east),
            north=min(MAX_MERCATOR_LAT, self.north),
        )

    def center(self) -> tuple[float, float]:
        return ((self.west + self.east) / 2.0, (self.south + self.north) / 2.0)


def union_bounds(extents: list[tuple[float, float, float, float]]) -> Bounds:
    if not extents:
        raise ValueError("at least one extent is required")
    west = min(extent[0] for extent in extents)
    south = min(extent[1] for extent in extents)
    east = max(extent[2] for extent in extents)
    north = max(extent[3] for extent in extents)
    return Bounds(west=west, south=south, east=east, north=north)


def lon_to_tile_x(lon: float, zoom: int) -> float:
    return ((lon + 180.0) / 360.0) * (1 << zoom)


def lat_to_tile_y(lat: float, zoom: int) -> float:
    lat = max(-MAX_MERCATOR_LAT, min(MAX_MERCATOR_LAT, lat))
    lat_rad = math.radians(lat)
    scale = 1 << zoom
    return (1.0 - math.asinh(math.tan(lat_rad)) / math.pi) * scale / 2.0


def tile_to_lon_lat(pixel_x: float, pixel_y: float, zoom: int) -> tuple[float, float]:
    scale = 1 << zoom
    lon = (pixel_x / scale) * 360.0 - 180.0
    n = math.pi * (1.0 - (2.0 * pixel_y / scale))
    lat = math.degrees(math.atan(math.sinh(n)))
    return lon, lat


def tile_bounds_xyz(x: int, y: int, zoom: int) -> Bounds:
    west, north = tile_to_lon_lat(x, y, zoom)
    east, south = tile_to_lon_lat(x + 1, y + 1, zoom)
    return Bounds(west=west, south=south, east=east, north=north)


def tiles_for_bounds(bounds: Bounds, zoom: int) -> list[tuple[int, int, int]]:
    bounds = bounds.clamped()
    epsilon = 1e-12
    min_x = int(math.floor(lon_to_tile_x(bounds.west, zoom)))
    max_x = int(math.floor(lon_to_tile_x(bounds.east - epsilon, zoom)))
    min_y = int(math.floor(lat_to_tile_y(bounds.north, zoom)))
    max_y = int(math.floor(lat_to_tile_y(bounds.south + epsilon, zoom)))
    limit = (1 << zoom) - 1
    min_x = max(0, min(limit, min_x))
    max_x = max(0, min(limit, max_x))
    min_y = max(0, min(limit, min_y))
    max_y = max(0, min(limit, max_y))
    return [(zoom, x, y) for x in range(min_x, max_x + 1) for y in range(min_y, max_y + 1)]
