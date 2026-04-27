---
description: Build HGT/SRTM to MapLibre raster-dem Terrain-RGB MBTiles converter
agent: build
---

Use the `gis-terrain` agent instructions and implement the complete terrain converter described in `AGENTS.md`.

Create this project structure:

```text
converter/
  pyproject.toml
  terrain_converter/
    __init__.py
    cli.py
    hgt.py
    bbox.py
    rgb.py
    tiling.py
    mbtiles.py
    tilejson.py
    style_json.py
    validate.py
  tests/
    test_hgt.py
    test_bbox.py
    test_rgb.py
    test_tiling.py
    test_mbtiles.py
    test_tilejson.py
    test_style_json.py

android-demo/
  TerrainDemoStyle.kt
  MapLibreTerrainUsage.kt
  README.md

docs/
  terrain-pipeline.md