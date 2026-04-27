"""Generate Terrain-RGB PNG tiles from HGT data."""

from __future__ import annotations

import binascii
import struct
import zlib
from pathlib import Path

from .bbox import Bounds, tile_to_lon_lat, tiles_for_bounds
from .hgt import HGTCollection
from .rgb import encode_elevation


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    crc = binascii.crc32(chunk_type)
    crc = binascii.crc32(data, crc) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", crc)


def write_png_rgba(width: int, height: int, rgba_bytes: bytes) -> bytes:
    stride = width * 4
    rows = [b"\x00" + rgba_bytes[row_start : row_start + stride] for row_start in range(0, len(rgba_bytes), stride)]
    compressed = zlib.compress(b"".join(rows), level=9)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return PNG_SIGNATURE + _png_chunk(b"IHDR", ihdr) + _png_chunk(b"IDAT", compressed) + _png_chunk(b"IEND", b"")


def generate_tile_rgba(collection: HGTCollection, zoom: int, x: int, y: int, tile_size: int = 256) -> bytes:
    pixels = bytearray(tile_size * tile_size * 4)
    for py in range(tile_size):
        for px in range(tile_size):
            world_x = x + ((px + 0.5) / tile_size)
            world_y = y + ((py + 0.5) / tile_size)
            lon, lat = tile_to_lon_lat(world_x, world_y, zoom)
            elevation = collection.sample(lon, lat)
            index = ((py * tile_size) + px) * 4
            if elevation is None:
                pixels[index : index + 4] = b"\x00\x00\x00\x00"
                continue
            red, green, blue = encode_elevation(elevation)
            pixels[index : index + 4] = bytes((red, green, blue, 255))
    return bytes(pixels)


def generate_tile_png(collection: HGTCollection, zoom: int, x: int, y: int, tile_size: int = 256) -> bytes:
    rgba = generate_tile_rgba(collection, zoom, x, y, tile_size=tile_size)
    return write_png_rgba(tile_size, tile_size, rgba)


def generate_xyz_tiles(collection: HGTCollection, bounds: Bounds, min_zoom: int, max_zoom: int) -> list[tuple[int, int, int]]:
    tiles: list[tuple[int, int, int]] = []
    for zoom in range(min_zoom, max_zoom + 1):
        tiles.extend(tiles_for_bounds(bounds, zoom))
    return tiles


def write_tile_file(tile_root: str | Path, zoom: int, x: int, y: int, tile_data: bytes) -> Path:
    tile_path = Path(tile_root) / str(zoom) / str(x) / f"{y}.png"
    tile_path.parent.mkdir(parents=True, exist_ok=True)
    tile_path.write_bytes(tile_data)
    return tile_path
