# Android demo

Use the generated `style.json` as the map style URL and serve the `terrain/` directory over HTTP.

The sample Kotlin files show the minimum MapLibre Native wiring for a separate `raster-dem` terrain source. Keep the DEM source separate from any base map MBTiles; the converter does not modify the base map database.
