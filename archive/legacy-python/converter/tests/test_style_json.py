from terrain_converter.style_json import build_style


def test_build_style_contains_separate_terrain_source():
    style = build_style("http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png")
    source = style["sources"]["terrain-dem"]
    assert source["type"] == "raster-dem"
    assert source["encoding"] == "mapbox"
    assert style["terrain"]["source"] == "terrain-dem"


def test_build_style_allows_custom_scheme_and_tile_size():
    style = build_style("http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png", scheme="tms", tile_size=512)
    source = style["sources"]["terrain-dem"]
    assert source["scheme"] == "tms"
    assert source["tileSize"] == 512
