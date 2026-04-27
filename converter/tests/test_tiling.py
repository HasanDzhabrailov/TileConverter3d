from __future__ import annotations

import argparse
import sqlite3
import struct
import zlib
from array import array

import pytest

from terrain_converter.bbox import tiles_for_bounds, union_bounds
from terrain_converter.cli import run_conversion
from terrain_converter.hgt import VOID_VALUE
from terrain_converter.hgt import load_hgt_collection
from terrain_converter.rgb import decode_elevation, encode_elevation
from terrain_converter.tiling import generate_tile_png, generate_tile_rgba


def write_hgt(path, size=1201, fill=0, north_value=None, south_value=None):
    sample_count = size * size
    samples = array("h", [fill]) * sample_count
    if north_value is not None:
        for col in range(size):
            samples[col] = north_value
    if south_value is not None:
        row_start = (size - 1) * size
        for col in range(size):
            samples[row_start + col] = south_value
    output = array("h", samples)
    output.byteswap()
    path.write_bytes(output.tobytes())


def decode_png_rgba(png_bytes):
    assert png_bytes.startswith(b"\x89PNG\r\n\x1a\n")
    offset = 8
    width = height = None
    idat = bytearray()
    while offset < len(png_bytes):
        length = struct.unpack(">I", png_bytes[offset : offset + 4])[0]
        chunk_type = png_bytes[offset + 4 : offset + 8]
        data = png_bytes[offset + 8 : offset + 8 + length]
        offset += 12 + length
        if chunk_type == b"IHDR":
            width, height = struct.unpack(">II", data[:8])
        elif chunk_type == b"IDAT":
            idat.extend(data)
        elif chunk_type == b"IEND":
            break
    raw = zlib.decompress(bytes(idat))
    stride = (width * 4) + 1
    rgba = bytearray()
    for row in range(height):
        row_data = raw[row * stride : (row + 1) * stride]
        assert row_data[0] == 0
        rgba.extend(row_data[1:])
    return width, height, bytes(rgba)


def test_generate_tile_rgba_uniform_value(tmp_path):
    hgt_path = tmp_path / "N00E000.hgt"
    write_hgt(hgt_path, fill=50)
    collection = load_hgt_collection([hgt_path])
    bounds = union_bounds([tile.extent for tile in collection.tiles])
    # Use the first intersecting tile to keep the sample fully inside the dataset.
    zoom, x, y = next(iter(tiles_for_bounds(bounds, 8)))
    rgba = generate_tile_rgba(collection, zoom, x, y, tile_size=8)
    expected = bytes(encode_elevation(50) + (255,))
    pixels = [rgba[index : index + 4] for index in range(0, len(rgba), 4)]
    opaque_pixels = [pixel for pixel in pixels if pixel[3] == 255]
    transparent_pixels = [pixel for pixel in pixels if pixel[3] == 0]

    assert opaque_pixels
    assert transparent_pixels
    assert set(opaque_pixels) == {expected}
    assert set(transparent_pixels) == {b"\x00\x00\x00\x00"}


def test_generate_tile_png_preserves_north_south_gradient(tmp_path):
    hgt_path = tmp_path / "N00E000.hgt"
    size = 1201
    samples = array("h", [0]) * (size * size)
    for row in range(size):
        value = 1000 - int((1000 * row) / (size - 1))
        row_start = row * size
        for col in range(size):
            samples[row_start + col] = value
    output = array("h", samples)
    output.byteswap()
    hgt_path.write_bytes(output.tobytes())
    collection = load_hgt_collection([hgt_path])
    bounds = union_bounds([tile.extent for tile in collection.tiles])
    zoom, x, y = next(iter(tiles_for_bounds(bounds, 8)))
    png_data = generate_tile_png(collection, zoom, x, y, tile_size=8)
    width, height, rgba = decode_png_rgba(png_data)
    rows = [rgba[row * width * 4 : (row + 1) * width * 4] for row in range(height)]
    top = None
    bottom = None
    for row in rows:
        for index in range(0, len(row), 4):
            if row[index + 3] == 255:
                top = decode_elevation(*row[index : index + 3])
                break
        if top is not None:
            break
    for row in reversed(rows):
        for index in range(0, len(row), 4):
            if row[index + 3] == 255:
                bottom = decode_elevation(*row[index : index + 3])
                break
        if bottom is not None:
            break
    assert top is not None
    assert bottom is not None
    assert top > bottom


def test_generate_tile_rgba_transparent_outside_dem_bounds(tmp_path):
    hgt_path = tmp_path / "N00E000.hgt"
    write_hgt(hgt_path, fill=50)
    collection = load_hgt_collection([hgt_path])
    bounds = union_bounds([tile.extent for tile in collection.tiles])
    zoom, x, y = next(iter(tiles_for_bounds(bounds, 8)))

    rgba = generate_tile_rgba(collection, zoom, x, y, tile_size=32)
    alphas = rgba[3::4]

    assert 0 in alphas
    assert 255 in alphas


def test_generate_tile_rgba_transparent_for_void_samples(tmp_path):
    hgt_path = tmp_path / "N00E000.hgt"
    write_hgt(hgt_path, fill=VOID_VALUE)
    collection = load_hgt_collection([hgt_path])
    bounds = union_bounds([tile.extent for tile in collection.tiles])
    zoom, x, y = next(iter(tiles_for_bounds(bounds, 8)))

    rgba = generate_tile_rgba(collection, zoom, x, y, tile_size=8)

    assert set(rgba[index : index + 4] for index in range(0, len(rgba), 4)) == {b"\x00\x00\x00\x00"}


def test_run_conversion_writes_outputs(tmp_path):
    hgt_path = tmp_path / "N00E000.hgt"
    write_hgt(hgt_path, fill=25)
    args = argparse.Namespace(
        inputs=[str(hgt_path)],
        output_mbtiles=str(tmp_path / "terrain-rgb.mbtiles"),
        tile_root=str(tmp_path / "terrain"),
        tilejson=str(tmp_path / "terrain" / "tiles.json"),
        style_json=str(tmp_path / "style.json"),
        tiles_url="http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png",
        minzoom=8,
        maxzoom=8,
        name="test-terrain",
    )
    result = run_conversion(args)

    assert result["tile_count"] > 0
    assert (tmp_path / "terrain" / "tiles.json").exists()
    assert (tmp_path / "style.json").exists()

    connection = sqlite3.connect(tmp_path / "terrain-rgb.mbtiles")
    try:
        tile_count = connection.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
    finally:
        connection.close()
    assert tile_count == result["tile_count"]
