"""Mapbox Terrain-RGB encoding helpers."""

from __future__ import annotations

import numpy as np


def encode_elevation(elevation_meters: float) -> tuple[int, int, int]:
    encoded = int(round((float(elevation_meters) + 10000.0) * 10.0))
    encoded = max(0, min(encoded, 256 * 256 * 256 - 1))
    return (encoded >> 16) & 255, (encoded >> 8) & 255, encoded & 255


def encode_elevation_array(elevations_meters: np.ndarray) -> np.ndarray:
    encoded = np.rint((elevations_meters.astype(np.float64, copy=False) + 10000.0) * 10.0)
    encoded = np.clip(encoded, 0, (256 * 256 * 256) - 1).astype(np.uint32, copy=False)
    return np.column_stack(
        (
            (encoded >> 16) & 255,
            (encoded >> 8) & 255,
            encoded & 255,
        )
    ).astype(np.uint8, copy=False)


def decode_elevation(red: int, green: int, blue: int) -> float:
    encoded = (int(red) << 16) + (int(green) << 8) + int(blue)
    return -10000.0 + (encoded * 0.1)
