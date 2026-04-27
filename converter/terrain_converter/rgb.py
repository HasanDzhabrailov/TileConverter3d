"""Mapbox Terrain-RGB encoding helpers."""

from __future__ import annotations


def encode_elevation(elevation_meters: float) -> tuple[int, int, int]:
    encoded = int(round((float(elevation_meters) + 10000.0) * 10.0))
    encoded = max(0, min(encoded, 256 * 256 * 256 - 1))
    return (encoded >> 16) & 255, (encoded >> 8) & 255, encoded & 255


def decode_elevation(red: int, green: int, blue: int) -> float:
    encoded = (int(red) << 16) + (int(green) << 8) + int(blue)
    return -10000.0 + (encoded * 0.1)
