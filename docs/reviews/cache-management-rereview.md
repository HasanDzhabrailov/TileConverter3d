# Cache Management Re-Review

Review date: 2026-05-07

Scope reviewed:

- `docs/reviews/cache-management-review.md`
- `kotlin/terrain-core/src/jvmMain/kotlin/com/terrainconverter/core/Conversion.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/ConversionRunner.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/JobManager.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/TerrainWebServer.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt`
- `kotlin/terrain-web/src/test/kotlin/com/terrainconverter/web/TerrainWebServerTest.kt`
- `kotlin/terrain-web-ui/src/jsTest/kotlin/com/terrainconverter/web/ui/PreviewStyleTest.kt`

## Findings

No findings.

The prior high-severity finding is addressed: running-job cleanup now calls `cancelAndJoin()` before job files are deleted, and the default conversion path has cooperative cancellation checks before tile writes and between render loop steps.

The prior medium-severity finding is addressed: the UI now shows a confirmation dialog with selected group counts before sending `DELETE /api/system/cache`, including an explicit warning when running jobs are selected.

## Residual Risks

- `DELETE /api/system/cache` will wait for active conversion cancellation to complete. This preserves filesystem safety, but a non-cooperative custom `conversionRunner` could still make the request wait indefinitely. The production conversion path is now cooperative.
- Multi-worker rendering may still finish in-flight CPU tile generation after cancellation is requested, but those worker tasks do not write job files directly; file deletion waits for the conversion coroutine to unwind.

## Verification Referenced

Previously passed after the fixes:

- `gradle :terrain-web:test`
- `gradle -p kotlin/terrain-web-ui jsNodeTest`
- `gradle :terrain-core:test`
- `gradle -p kotlin/terrain-web-ui syncFrontendDist`
