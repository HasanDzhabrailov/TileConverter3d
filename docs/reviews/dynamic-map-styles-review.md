# Dynamic Map Styles Review

Review command: `/review-dynamic-map-styles`

Date: 2026-05-05

Resolution: fixed on 2026-05-05; verification passed with `gradle :terrain-web:test`.

## Findings

1. Medium - Uploaded raster-dem MBTiles dynamic styles drop the `scheme` field from the terrain source.

   Status: Fixed.

   Location: `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/DynamicMapStyles.kt:61`

   The previous MBTiles raster-dem style path used `buildStyle(..., scheme = "xyz", encoding = "mapbox", tileSize = 256)` and emitted `"scheme": "xyz"` for the DEM source. The dynamic implementation now builds the source map manually and only adds `encoding` for `raster-dem`, so both `/api/mbtiles/{tilesetId}/style` and `/api/mbtiles/{tilesetId}/style-mobile` omit the scheme. This conflicts with the review focus and repo constraint to preserve terrain source scheme defaults. The locked fixtures were updated to remove `scheme`, which hides the regression instead of protecting the contract.

2. Medium - Dynamic job styles do not have locked JSON contract tests.

   Status: Fixed.

   Location: `kotlin/terrain-web/src/test/kotlin/com/terrainconverter/web/TerrainWebServerTest.kt:423`

   The only completed-job dynamic style test checks substring presence for one custom base source and mobile host. It does not parse and assert the full JSON shape for `/api/jobs/{jobId}/style` and `/api/jobs/{jobId}/style-mobile`, so regressions in source fields such as `encoding`, `tileSize`, `scheme`, `terrain`, `center`, `zoom`, layer order, or accidental absolute desktop URLs can pass unnoticed.

3. Low - `base=none` is not tested for job styles or mobile styles.

   Status: Fixed.

   Location: `kotlin/terrain-web/src/test/kotlin/com/terrainconverter/web/TerrainWebServerTest.kt:459`

   Current coverage verifies `base=none` only for desktop uploaded raster MBTiles. The plan requires `base=none` to generate terrain/hillshade without a raster base layer across dynamic style endpoints. Job desktop/mobile styles and MBTiles mobile styles should have explicit assertions that no `base-map` source/layer is emitted while terrain/hillshade remains present where applicable.

4. Low - Unknown base source handling is only tested for the completed job desktop endpoint.

   Status: Fixed.

   Location: `kotlin/terrain-web/src/test/kotlin/com/terrainconverter/web/TerrainWebServerTest.kt:456`

   The implementation wires unknown-base handling in four routes, but tests cover only `/api/jobs/{jobId}/style?base=missing`. There are no assertions for `/style-mobile`, `/api/mbtiles/{tilesetId}/style`, or `/api/mbtiles/{tilesetId}/style-mobile`, and no assertion that the response body contains the Russian error detail required by the plan.

## Notes

- Selected custom base source behavior is implemented for completed job desktop/mobile styles, including relative desktop URLs and mobile host expansion.
- Missing `base` defaults to `openstreetmap` through `resolveStyleBaseSource`.
- `base=none` is now covered for job mobile and MBTiles desktop/mobile styles.
- Unknown base handling is now covered for job desktop/mobile and MBTiles desktop/mobile styles, including the Russian error detail.
- Raster-dem MBTiles style fixtures now preserve `scheme: "xyz"`.
