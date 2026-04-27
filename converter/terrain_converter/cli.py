"""CLI entrypoint for terrain conversion."""

from __future__ import annotations

import argparse
import os
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

from .bbox import union_bounds
from .hgt import load_hgt_collection
from .mbtiles import MBTilesWriter
from .style_json import build_style, write_style
from .tilejson import build_tilejson, write_tilejson
from .tiling import count_xyz_tiles, generate_tile_png, generate_xyz_tiles, write_tile_file
from .validate import validate_inputs, validate_zoom_range


DEFAULT_WORKERS = max(1, (os.cpu_count() or 1) - 1)
_WORKER_COLLECTION = None


def _init_tile_worker(input_paths: list[str]) -> None:
    global _WORKER_COLLECTION
    _WORKER_COLLECTION = load_hgt_collection(input_paths)


def _render_tile(task: tuple[int, int, int]) -> tuple[int, int, int, bytes]:
    if _WORKER_COLLECTION is None:
        raise RuntimeError("tile worker was not initialized")
    zoom, x, y = task
    return zoom, x, y, generate_tile_png(_WORKER_COLLECTION, zoom, x, y)


def _iter_rendered_tiles(collection, input_paths: list[str], tiles, workers: int):
    if workers <= 1:
        for zoom, x, y in tiles:
            yield zoom, x, y, generate_tile_png(collection, zoom, x, y)
        return

    with ProcessPoolExecutor(max_workers=workers, initializer=_init_tile_worker, initargs=([str(path) for path in input_paths],)) as executor:
        yield from executor.map(_render_tile, tiles, chunksize=8)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Convert HGT/SRTM tiles into MapLibre Terrain-RGB tiles")
    parser.add_argument("inputs", nargs="+", help="HGT files or directories")
    parser.add_argument("--output-mbtiles", default="terrain-rgb.mbtiles", help="Output MBTiles path")
    parser.add_argument("--tile-root", default="terrain", help="Output tile directory root")
    parser.add_argument("--tilejson", default="terrain/tiles.json", help="Output TileJSON path")
    parser.add_argument("--style-json", default="style.json", help="Output style.json path")
    parser.add_argument("--tiles-url", default="http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png", help="Public terrain tile URL template")
    parser.add_argument("--minzoom", type=int, default=8, help="Minimum output zoom")
    parser.add_argument("--maxzoom", type=int, default=12, help="Maximum output zoom")
    parser.add_argument("--name", default="terrain-dem", help="MBTiles metadata name")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS, help="Worker process count for tile rendering")
    return parser


def run_conversion(args: argparse.Namespace) -> dict[str, object]:
    validate_zoom_range(args.minzoom, args.maxzoom)
    input_paths = validate_inputs(args.inputs)
    collection = load_hgt_collection(input_paths)
    bounds = union_bounds([tile.extent for tile in collection.tiles]).clamped()
    tile_count = count_xyz_tiles(bounds, args.minzoom, args.maxzoom)
    tiles = generate_xyz_tiles(collection, bounds, args.minzoom, args.maxzoom)
    workers = max(1, getattr(args, "workers", 1))

    with MBTilesWriter(args.output_mbtiles) as writer:
        writer.write_metadata(
            {
                "name": args.name,
                "type": "overlay",
                "version": "1",
                "format": "png",
                "bounds": ",".join(str(value) for value in [bounds.west, bounds.south, bounds.east, bounds.north]),
                "minzoom": str(args.minzoom),
                "maxzoom": str(args.maxzoom),
            }
        )
        for zoom, x, y, png_data in _iter_rendered_tiles(collection, input_paths, tiles, workers):
            writer.write_tile(zoom, x, y, png_data)
            write_tile_file(args.tile_root, zoom, x, y, png_data)

    tilejson = build_tilejson(bounds, args.minzoom, args.maxzoom, args.tiles_url)
    write_tilejson(args.tilejson, tilejson)
    style = build_style(args.tiles_url)
    write_style(args.style_json, style)
    return {
        "bounds": bounds,
        "tile_count": tile_count,
        "output_mbtiles": str(Path(args.output_mbtiles)),
        "tilejson": str(Path(args.tilejson)),
        "style_json": str(Path(args.style_json)),
    }


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    result = run_conversion(args)
    print(f"Generated {result['tile_count']} terrain tiles")
    print(f"MBTiles: {result['output_mbtiles']}")
    print(f"TileJSON: {result['tilejson']}")
    print(f"Style: {result['style_json']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
