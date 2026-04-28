from terrain_converter.bbox import Bounds
from terrain_converter.tilejson import build_tilejson


def test_build_tilejson_contains_maplibre_dem_fields():
    tilejson = build_tilejson(
        Bounds(west=10.0, south=20.0, east=11.0, north=21.0),
        min_zoom=8,
        max_zoom=12,
        tiles_url="http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png",
    )
    assert tilejson["type"] == "raster-dem"
    assert tilejson["encoding"] == "mapbox"
    assert tilejson["tileSize"] == 256
    assert tilejson["scheme"] == "xyz"


def test_build_tilejson_allows_custom_scheme_and_tile_size():
    tilejson = build_tilejson(
        Bounds(west=10.0, south=20.0, east=11.0, north=21.0),
        min_zoom=8,
        max_zoom=12,
        tiles_url="http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png",
        scheme="tms",
        tile_size=512,
    )
    assert tilejson["scheme"] == "tms"
    assert tilejson["tileSize"] == 512
