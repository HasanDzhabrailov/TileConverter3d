import pytest

from terrain_converter.rgb import decode_elevation, encode_elevation


def test_encode_decode_zero():
    encoded = encode_elevation(0.0)
    assert encoded == (1, 134, 160)
    assert decode_elevation(*encoded) == 0.0


def test_encode_decode_signed_elevations():
    for elevation in (-123.4, 456.7):
        decoded = decode_elevation(*encode_elevation(elevation))
        assert decoded == pytest.approx(elevation)
