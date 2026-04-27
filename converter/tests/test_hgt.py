from __future__ import annotations

from array import array
import math

import pytest

from terrain_converter.hgt import infer_hgt_size, parse_hgt_coordinate, read_hgt
from terrain_converter.validate import validate_inputs


def write_hgt(path, size=1201, fill=0, updates=None):
    sample_count = size * size
    samples = array("h", [fill]) * sample_count
    for row, col, value in updates or []:
        samples[(row * size) + col] = value
    output = array("h", samples)
    output.byteswap()
    path.write_bytes(output.tobytes())


def test_parse_hgt_coordinate():
    coordinate = parse_hgt_coordinate("S12W123.hgt")
    assert coordinate.lat == -12
    assert coordinate.lon == -123


def test_infer_size_and_orientation(tmp_path):
    hgt_path = tmp_path / "N00E000.hgt"
    write_hgt(
        hgt_path,
        updates=[
            (0, 0, 1000),
            (0, 1200, 2000),
            (1200, 0, -100),
            (1200, 1200, 300),
        ],
    )

    assert infer_hgt_size(hgt_path) == 1201

    tile = read_hgt(hgt_path)
    assert tile.sample_bilinear(0.0, math.nextafter(1.0, 0.0)) == pytest.approx(1000)
    assert tile.sample_bilinear(1.0, 1.0) is None
    assert tile.sample_bilinear(0.999999999999, 0.999999999999) == pytest.approx(2000)
    assert tile.sample_bilinear(0.0, 0.0) == -100
    assert tile.sample_bilinear(0.999999999999, 0.0) == pytest.approx(300)


def test_validate_inputs_discovers_uppercase_hgt(tmp_path):
    hgt_path = tmp_path / "N00E000.HGT"
    write_hgt(hgt_path)

    discovered = validate_inputs([tmp_path])

    assert discovered == [hgt_path]
