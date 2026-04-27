from terrain_converter.bbox import Bounds, tile_bounds_xyz, tiles_for_bounds, union_bounds


def test_union_bounds():
    bounds = union_bounds([(10.0, 20.0, 11.0, 21.0), (11.0, 19.5, 12.0, 20.5)])
    assert bounds == Bounds(west=10.0, south=19.5, east=12.0, north=21.0)


def test_tiles_for_exact_tile_bounds():
    bounds = tile_bounds_xyz(1, 1, 1)
    assert tiles_for_bounds(bounds, 1) == [(1, 1, 1)]
