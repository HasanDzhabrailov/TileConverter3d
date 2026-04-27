# Repo Notes

- The real project lives under `converter/`. Root also contains `android-demo/`, `docs/`, and repo-local OpenCode prompts in `.opencode/agents/gis-terrain.md` and `.opencode/commands/build-terrain-converter.md`.
- There is no app/framework workspace here; `converter/pyproject.toml` is the executable source of truth.

# Commands

- Install for local work: `python -m pip install -e ./converter[test]`
- Run tests: `python -m pytest` from `converter/`
- If `pytest` is not installed yet, `python -m pytest` fails with `No module named pytest`; `python -m compileall terrain_converter tests` is the fastest smoke check.
- Run the converter from `converter/`: `python -m terrain_converter.cli <hgt file or dir> --minzoom 8 --maxzoom 12`

# Structure

- `converter/terrain_converter/cli.py` orchestrates the full pipeline: validate inputs, load HGT, generate PNG DEM tiles, write MBTiles, then emit `terrain/tiles.json` and `style.json`.
- `converter/terrain_converter/hgt.py` is the critical HGT reader/sampler: files are signed 16-bit big-endian and only `1201x1201` and `3601x3601` inputs are accepted.
- `converter/terrain_converter/tiling.py` writes exact 8-bit RGBA PNGs; `R,G,B` hold Terrain-RGB values and pixels outside DEM coverage or on unresolved voids are fully transparent.
- `converter/terrain_converter/mbtiles.py` writes XYZ tiles into MBTiles with a required TMS row flip.

# Repo-Specific Constraints

- Keep the terrain DEM separate from any base map MBTiles. The current converter writes a new `terrain-rgb.mbtiles`; it does not patch another database.
- Mixed HGT resolutions are rejected in `validate.py`; do not silently resample `1201` and `3601` tiles together without changing validation and tests.
- Output compatibility target is MapLibre `raster-dem` with `encoding: mapbox`, `tileSize: 256`, and `scheme: xyz`. Preserve the default tile URL template `http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png` unless the task explicitly changes hosting.
- The converter writes both MBTiles and a filesystem tile pyramid under `terrain/{z}/{x}/{y}.png`. If you change outputs, update `tilejson.py`, `style_json.py`, CLI defaults, and the Android demo together.

# Verification Targets

- High-value tests live in `converter/tests/` and cover HGT parsing/orientation, bounds math, Terrain-RGB encoding, MBTiles row addressing, and end-to-end output creation.
- When changing sampling, tiling, or encoding behavior, run at least `test_hgt.py`, `test_tiling.py`, `test_mbtiles.py`, and the smoke conversion path.
