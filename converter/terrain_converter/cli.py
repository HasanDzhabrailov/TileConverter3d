"""CLI entrypoint for terrain conversion."""

from __future__ import annotations

import argparse
from pathlib import Path

from .bbox import union_bounds
from .hgt import load_hgt_collection
from .mbtiles import MBTilesWriter
from .style_json import build_style, write_style
from .tilejson import build_tilejson, write_tilejson
from .tiling import generate_tile_png, generate_xyz_tiles, write_tile_file
from .validate import validate_inputs, validate_zoom_range


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
    return parser


def run_conversion(args: argparse.Namespace) -> dict[str, object]:
    validate_zoom_range(args.minzoom, args.maxzoom)
    input_paths = validate_inputs(args.inputs)
    collection = load_hgt_collection(input_paths)
    bounds = union_bounds([tile.extent for tile in collection.tiles]).clamped()
    tiles = generate_xyz_tiles(collection, bounds, args.minzoom, args.maxzoom)

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
        for zoom, x, y in tiles:
            png_data = generate_tile_png(collection, zoom, x, y)
            writer.write_tile(zoom, x, y, png_data)
            write_tile_file(args.tile_root, zoom, x, y, png_data)

    tilejson = build_tilejson(bounds, args.minzoom, args.maxzoom, args.tiles_url)
    write_tilejson(args.tilejson, tilejson)
    style = build_style(args.tiles_url)
    write_style(args.style_json, style)
    return {
        "bounds": bounds,
        "tile_count": len(tiles),
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
