# Terrain Pipeline

1. Validate input `.hgt` files, including filename coordinates and supported grid sizes: `1201×1201` or `3601×3601`.
2. Read signed 16-bit big-endian elevation samples into a virtual DEM mosaic.
3. Compute XYZ output coverage for the requested zoom range from the union of input HGT bounds.
4. Sample each output pixel in Web Mercator, bilinearly interpolate elevation, and encode it as Terrain-RGB.
5. Write each `256×256` terrain PNG tile to both:
   - `terrain/{z}/{x}/{y}.png`
   - `terrain-rgb.mbtiles`
6. Write `terrain/tiles.json` and `style.json`.

## Notes

- Filesystem tiles are written as XYZ.
- MBTiles rows are stored as TMS internally.
- The Kotlin implementation writes MBTiles natively through SQLite JDBC.
- Supported encodings: `mapbox` (default), `terrarium`.

## Default TileJSON

```json
{
  "type": "raster-dem",
  "tiles": ["http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png"],
  "encoding": "mapbox",
  "tileSize": 256,
  "scheme": "xyz"
}
```
