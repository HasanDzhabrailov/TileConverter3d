# Terrain pipeline

1. Validate the input `.hgt` files, including filename coordinates and supported `1201x1201` / `3601x3601` sizes.
2. Read signed 16-bit big-endian elevation samples into a virtual DEM mosaic indexed by the HGT tile southwest corner.
3. Compute XYZ tile coverage for the requested zoom range from the union of the input HGT extents.
4. Sample each output pixel in Web Mercator, bilinearly interpolate elevation, and encode it with the Mapbox Terrain-RGB formula.
5. Write the resulting `256x256` PNG tiles both to `terrain/{z}/{x}/{y}.png` and to `terrain-rgb.mbtiles` using MBTiles TMS row numbering.
6. Emit `terrain/tiles.json` and `style.json` for MapLibre clients.

Default public tile template:

```json
{
  "type": "raster-dem",
  "tiles": ["http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png"],
  "encoding": "mapbox",
  "tileSize": 256,
  "scheme": "xyz"
}
```
