# Terrain UI Redesign Status

Status: COMPLETE ✅

This file is the canonical saved status for the Terrain UI/UX redesign across OpenCode sessions.

Update this file after every non-review build stage.

## Current Phase

- phase: **COMPLETE - Finalized**
- command: `/finalize-terrain-ui-redesign`
- date: 2026-05-07
- owner/session: OpenCode
- status: All review findings addressed, tests passing, redesign finalized
- blockers: none
- next command: None - redesign complete

## Requirements

- ✅ Russian-only user interface.
- ✅ Default Preview shows normal 2D OpenStreetMap.
- ✅ `Uploaded base` is removed from Preview.
- ✅ 3D Preview is available only after a completed HGT conversion or a selected MBTiles with source type `raster-dem`.
- ✅ User clearly understands that 3D requires converting HGT/ZIP or uploading a ready DEM MBTiles.
- ✅ Conversion completion automatically selects the completed job and switches Preview to 3D.
- ✅ Users can choose builtin base map sources and add their own tile sources.
- ✅ Custom base map sources are global and persisted on the backend in SQLite.
- ✅ Desktop style endpoints change according to selected `base`.
- ✅ Mobile style endpoints also change according to selected `base`.
- ✅ Mobile server links are copyable from the UI and use the active LAN/mobile address, not localhost.
- ✅ MBTiles tile server startup shows real-time progress before the server is ready.
- ✅ Existing uploaded MBTiles server list remains visible and usable.
- ✅ Cache can be cleared with checkboxes for selected groups.
- ✅ Clearing selected cache removes data from disk, backend state, and UI lists.

## Completed Work

- Created `.opencode/commands/` workflow command files for plan, build, review, and finalization stages.
- Removed old unrelated `.opencode/commands/` entries from the command menu; historical docs in `docs/` were preserved.
- Created `docs/terrain-ui-redesign-plan.md` with the full product and implementation plan.
- Created `docs/terrain-ui-redesign-runbook.md` for cross-session continuation.
- Created this status file.
- Refreshed `docs/terrain-ui-redesign-plan.md` to explicitly cover Russian-only UI copy, 2D OpenStreetMap default Preview, 3D gating and conversion auto-switch, removal of `Uploaded base`, SQLite-backed global base sources, dynamic desktop/mobile style endpoints with `?base={sourceId}`, LAN/mobile copy links, real-time MBTiles progress, selectable cache clearing, and saved review workflow.
- Reviewed the saved plan and wrote findings to `docs/reviews/terrain-ui-redesign-plan-review.md`.
- Added SQLite-backed global base map source storage at `data/app.sqlite` through the terrain web storage root.
- Seeded builtin base sources on backend startup: `openstreetmap`, `opentopomap`, `cartodb-positron`, `cartodb-dark-matter`, `esri-satellite`, and `none`.
- Added base source CRUD API routes: `GET /api/base-sources`, `POST /api/base-sources`, `PUT /api/base-sources/{sourceId}`, and `DELETE /api/base-sources/{sourceId}`.
- Added validation for custom source name, URL template, attribution normalization, and `max_zoom`, with builtin sources protected from edit/delete.
- Added backend tests for builtin seeding, custom persistence, custom update/delete, validation rejection, and builtin mutation rejection.
- Added dynamic style generation for job and MBTiles desktop/mobile endpoints with `?base={sourceId}` selection.
- Defaulted missing `base` to `openstreetmap`, supported `base=none`, and returned a Russian not-found API error for unknown base sources.
- Updated locked backend MBTiles style fixtures for the new default OpenStreetMap base layer behavior.
- Fixed dynamic map style review findings: restored MBTiles raster-dem `scheme`, expanded JSON assertions for job styles, and covered `base=none` plus unknown base errors across desktop/mobile endpoints.
- Replaced separate terrain/MBTiles preview panels with a single `Предпросмотр карты` panel that opens in 2D OpenStreetMap by default.
- Removed `Uploaded base` from preview base choices; preview base is now `OpenStreetMap` or `Без подложки`.
- Disabled 3D preview until a completed conversion job or selected uploaded `raster-dem` MBTiles source exists, with Russian guidance.
- Added explicit `Открыть в 2D` for raster MBTiles and `3D доступен` / `Открыть в 3D` for DEM MBTiles.
- Auto-selects newly completed conversion jobs and switches preview to `3D рельеф` with a Russian ready notice.
- Fixed preview review findings from `docs/reviews/preview-2d-3d-ux-review.md`: explicit DEM MBTiles preview now wins over selected completed jobs, existing DEM tilesets are not auto-selected on bootstrap, preview base sources load from `/api/base-sources`, and job/MBTiles style links append `?base={selectedSourceId}`.
- Added focused Kotlin/JS tests for conversion-complete preview switching, DEM MBTiles preview source selection, bootstrap tileset non-selection, style URL base query generation, and terrain scheme preservation.
- Fixed the final re-review edge case: clicking a completed job after opening DEM MBTiles in 3D now makes that job the active 3D preview source, with regression test coverage.
- Added MBTiles upload progress API state at `/api/mbtiles/uploads/{uploadId}/progress` while preserving the existing synchronous `/api/mbtiles` response contract.
- Added frontend MBTiles upload progress with file name, percent, byte counts, speed, server-side preparation stages, cancel during browser upload, and persistent localized error/retry guidance.
- Added backend tests for MBTiles upload progress reaching `ready` and recording validation errors.
- Fixed follow-up review findings: active XHR is aborted when the upload form is disposed, stale upload progress entries are TTL-cleaned, raw JSON error payloads are normalized before localization, and TTL cleanup has backend test coverage.
- Added `GET /api/system/storage` with byte totals and counts for job/cache groups, uploaded MBTiles, and custom base sources.
- Added `DELETE /api/system/cache` with selectable clearing for completed jobs, failed jobs, pending/running jobs, uploaded MBTiles tilesets, and custom base sources while preserving builtin base sources.
- Added running-job cancellation/removal support in the backend job manager so cache cleanup can remove active job state and files safely.
- Added a Russian `Система и кэш` UI panel with checkboxes, storage stats, selected cleanup, refresh, and UI state cleanup after deletion.
- Added backend coverage for storage stats and selected cache clearing across completed jobs, uploaded MBTiles, and custom sources.
- Fixed cache management review findings from `docs/reviews/cache-management-review.md`: running-job cleanup now cancels and joins active work before deleting job files, conversion checks cancellation between stages/tiles, and the UI shows a confirmation summary before destructive cleanup.
- Added regression coverage for running-job cleanup waiting for cancellation and cache-clear confirmation warning text.
- Replaced remaining English frontend labels, hints, warnings, form labels, statuses, and copy feedback with Russian copy while preserving technical names such as MBTiles, TileJSON, xyz, and tms.
- Added copyable LAN/mobile job links for terrain tiles, style, style-mobile, and TileJSON; style links include the selected `base` source.
- Polished MBTiles mobile copy links so tiles, style, style-mobile, and TileJSON use the active phone/LAN address, with selected `base` appended to style endpoints and Russian copy states: `Копировать`, `Скопировано`, `Не удалось скопировать`.

### Finalization Fixes (2026-05-07)

- **Fixed Russian UI Polish Review findings** (`docs/reviews/russian-ui-polish-review.md`):
  1. MBTiles copy buttons now only render when `activeMobileAddress` is present, preventing localhost URLs from being copied
  2. MBTiles list now displays Russian labels (`Растровая карта`, `Рельеф DEM`) instead of raw API values (`raster`, `raster-dem`)
  3. MBTiles card copy buttons now have visible target labels: `Копировать тайлы`, `Копировать стиль`, `Копировать стиль для телефона`, `Копировать TileJSON`
- Fixed test assertion to match actual Russian copy with ё character: "завершённые" instead of "завершенные"

## In Progress

- None.

## Remaining Work

- None - redesign complete.

## Verification

- ✅ Passed: `gradle :terrain-web:test`.
- ✅ Passed: `gradle :terrain-web:test` after dynamic map style fixture updates.
- ✅ Passed: `gradle :terrain-web:test` after fixing dynamic map style review findings.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui syncFrontendDist` after 2D/3D preview UX changes.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui jsNodeTest` after preview review fixes.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui syncFrontendDist` after preview review fixes.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui jsNodeTest` after final preview edge-case fix.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui syncFrontendDist` after final preview edge-case fix.
- ✅ Passed: `gradle :terrain-web:test` after MBTiles upload progress review fixes.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui syncFrontendDist` after MBTiles upload progress review fixes.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui jsNodeTest` after MBTiles upload progress review fixes.
- ✅ Passed: `gradle :terrain-web:test` after cache management implementation.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui syncFrontendDist` after cache management implementation.
- ✅ Passed: `gradle :terrain-web:test` after cache management review fixes.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui jsNodeTest` after cache management review fixes.
- ✅ Passed: `gradle :terrain-core:test` after conversion cancellation hook changes.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui syncFrontendDist` after cache management review fixes.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui syncFrontendDist` after Russian UI and mobile link polish.
- ✅ Passed: `gradle -p kotlin/terrain-web-ui jsNodeTest` after Russian UI polish review fixes.
- ✅ Passed: `gradle test` full test suite.

## Files Changed

- `.opencode/commands/*.md`
- `docs/terrain-ui-redesign-plan.md`
- `docs/terrain-ui-redesign-runbook.md`
- `docs/terrain-ui-redesign-status.md` (this file)
- `docs/reviews/terrain-ui-redesign-plan-review.md`
- `docs/reviews/russian-ui-polish-review.md`
- `docs/reviews/README.md`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/BaseMapSourceRepository.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/Dependencies.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/Models.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/Dependencies.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/Storage.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/TerrainWebServer.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/JobManager.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/DynamicMapStyles.kt`
- `kotlin/terrain-web/src/test/kotlin/com/terrainconverter/web/TerrainWebServerTest.kt`
- `kotlin/terrain-web/src/test/resources/fixtures/contracts/backend/mbtiles/style*.json`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/AppState.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Api.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Models.kt`
- `kotlin/terrain-web-ui/src/jsMain/resources/app.css`
- `kotlin/terrain-web-ui/src/jsTest/kotlin/com/terrainconverter/web/ui/PreviewStyleTest.kt`

## Next Command

None - Terrain UI Redesign is **COMPLETE** ✅.

## Summary

The Terrain Converter UI/UX redesign has been successfully completed. All requirements have been implemented:

1. **Russian-only UI**: All user-facing strings are in Russian, with technical terms preserved
2. **2D Default Preview**: Opens with OpenStreetMap in 2D mode
3. **3D Gating**: 3D terrain preview only available with completed conversion or DEM MBTiles
4. **Auto-switch**: Conversion completion automatically selects job and switches to 3D
5. **Global Base Sources**: SQLite-backed custom sources with dynamic style generation
6. **Mobile Links**: Copyable LAN/mobile addresses with selected base source
7. **Upload Progress**: Real-time MBTiles upload progress with cancel support
8. **Cache Management**: Selectable cache clearing with confirmation

All backend and frontend tests pass. The redesign is ready for production use.
