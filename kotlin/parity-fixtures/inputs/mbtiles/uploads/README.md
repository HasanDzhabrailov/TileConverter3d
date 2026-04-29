# Uploaded MBTiles Inputs

This directory contains committed binary `.mbtiles` fixtures for upload parity testing.

## Files

- `raster.mbtiles` - Valid raster tileset with complete metadata
- `raster-dem.mbtiles` - Raster DEM tileset for auto-detection testing
- `malformed-metadata.mbtiles` - Tileset with malformed metadata values (for error handling tests)
- `missing-metadata.mbtiles` - Tileset with minimal metadata (for default value tests)

## Generation

Run `python3 generate_fixtures.py` to regenerate these fixtures from the source script.

The fixtures are SQLite databases following the MBTiles specification with:
- `metadata` table: key-value pairs for tileset metadata
- `tiles` table: zoom_level, tile_column, tile_row, tile_data (BLOB)
