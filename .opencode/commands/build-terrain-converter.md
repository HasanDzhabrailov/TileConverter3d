---
description: Build HGT/SRTM to MapLibre raster-dem Terrain-RGB MBTiles converter
agent: build
---

Use the `gis-terrain` agent instructions and implement the complete terrain converter described in `AGENTS.md`.

Create this project structure:

```text
kotlin/
  terrain-core/
  terrain-cli/

android-demo/
  TerrainDemoStyle.kt
  MapLibreTerrainUsage.kt
  README.md

docs/
  terrain-pipeline.md

Requirements:

- Kotlin/KMP runtime only
- no Python dependency in conversion, MBTiles, scripts, Docker, or docs
- preserve established converter behavior and output semantics
