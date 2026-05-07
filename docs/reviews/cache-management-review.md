# Cache Management Review

Review date: 2026-05-07

Resolution update: findings were addressed on 2026-05-07. Running-job cleanup now cancels and joins active work before deleting files, conversion checks cancellation between stages/tiles, and the UI asks for confirmation with selected counts before clearing.

Scope reviewed:

- `AGENTS.md`
- `docs/terrain-ui-redesign-plan.md`
- `docs/terrain-ui-redesign-status.md`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/TerrainWebServer.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/JobManager.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/Storage.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/BaseMapSourceRepository.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/ConversionRunner.kt`
- `kotlin/terrain-web/src/test/kotlin/com/terrainconverter/web/TerrainWebServerTest.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/AppState.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Api.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Models.kt`

## Findings

1. High - Running job cleanup can delete files while conversion work is still writing.

   `JobManager.deleteJobsByStatus()` cancels the coroutine and removes the job state, and `clearSelectedCache()` immediately deletes the job directory. The conversion path then enters blocking conversion work through `runTerrainConversion(...)` without visible cancellation checkpoints before filesystem writes complete. If conversion does not observe coroutine cancellation promptly, cleanup can remove or race with files that the still-running conversion is writing, leaving partial recreated output or failed writes outside backend state. This breaks the safe deletion semantics required for running jobs.

   References:

   - `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/JobManager.kt:43`
   - `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/TerrainWebServer.kt:526`
   - `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/ConversionRunner.kt:72`

   Recommendation: make active-job cleanup two-phase. Mark the job cancelling, cancel it, wait for the coroutine/conversion to stop or expose cooperative cancellation through the conversion pipeline, then delete the job directory. Until that exists, disable running-job deletion or leave the checkbox unavailable with the required Russian explanation.

2. Medium - The UI clears destructive selections without the required confirmation summary.

   The plan requires a confirmation summary before deletion. `SystemCachePanel.clearSelected()` sends `DELETE /api/system/cache` directly when `Очистить выбранное` is clicked. This is especially risky when `Выполняющиеся задания` is selected because it can cancel active conversion work and delete job files with no final summary of affected groups.

   References:

   - `docs/terrain-ui-redesign-plan.md:328`
   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:132`
   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:188`

   Recommendation: add a confirmation step showing selected groups and counts from current storage stats. Include an explicit warning when running jobs are selected.

## Notes

- Builtin source preservation is implemented correctly for cache cleanup: `deleteCustomSources()` deletes only rows where `is_builtin = 0`, and existing tests assert that `openstreetmap` remains after cleanup.
- UI refresh after successful cleanup is mostly consistent: the panel updates returned storage stats, reloads bootstrap data, and `AppState.applyBootstrap()` removes stale selected jobs, tilesets, terrain preview sources, and falls back selected base source to `openstreetmap` when a custom source disappears.
- Filesystem and backend state consistency is covered for completed jobs, uploaded MBTiles, and custom sources, but there is no focused backend test for pending/running job cleanup or cancellation race behavior.

## Suggested Verification After Fixes

- Add backend coverage for clearing pending and running jobs.
- Add a test/fake conversion runner that suspends or blocks long enough to verify cleanup does not delete the job directory until the running job is stopped.
- Add frontend coverage or manual verification for the confirmation summary and post-clear bootstrap refresh.
