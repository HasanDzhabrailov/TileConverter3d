# Kotlin Web UI Migration Status

Status: COMPLETE - KOTLIN WEB UI CUTOVER

This file is the canonical saved status for multi-session Kotlin Web UI migration work.
Update it after every non-review implementation stage.

## Current Phase

- phase: Phase 10 - Cleanup
- owner/session: OpenCode
- date: 2026-05-04
- status: complete
- previous phase: Phase 9 - Verification
- next phase: none

## Current Objective

- complete Kotlin-only web UI cutover cleanup by removing the legacy React/Vite frontend from the supported path and aligning Docker, helper scripts, and docs with the Kotlin/JS Compose UI

## Completed

- plan review workflow completed
- migration plan tightened to preserve external contract, upload parity, preview behavior, build/cutover detail, and parity-oriented verification
- Phase 1 - Tech Spike completed
- chose Kotlin/JS IR browser target with Compose Multiplatform HTML UI
- locked browser API approach: native `fetch`, `FormData`, and `WebSocket` from Kotlin/JS
- locked `MapLibre GL JS` integration approach: thin Kotlin external declarations over the existing JS library
- saved the spike result in `docs/kotlin-web-ui-tech-spike.md`
- Phase 2 - Module Setup completed
- added Gradle module `kotlin/terrain-web-ui/`
- kept the new UI module isolated from the root build so the existing Kotlin/KMP toolchain remains unchanged
- configured Kotlin/JS IR browser target with Compose Multiplatform HTML
- added Gradle-managed `maplibre-gl` JS dependency wiring
- locked production build task `gradle -p kotlin/terrain-web-ui jsBrowserDistribution`
- locked backend-facing sync task `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- locked generated production assets directory `kotlin/terrain-web-ui/build/dist/js/productionExecutable`
- locked Ktor/Docker-compatible served distribution directory `web/frontend/dist`
- kept `TERRAIN_WEB_FRONTEND_DIST` compatibility by preserving `web/frontend/dist` as the served path
- locked local development workflow to `gradle :terrain-web:run` plus `gradle -p kotlin/terrain-web-ui jsBrowserDevelopmentRun`
- Phase 3 - Models and API completed
- ported frontend JSON models to Kotlin `@Serializable` types in `terrain-web-ui`
- preserved snake_case field names and optional payload behavior for jobs, artifacts, MBTiles tilesets, server-info, and websocket events
- added Kotlin/JS fetch wrappers for `/api/server-info`, `/api/jobs`, `/api/jobs/{jobId}`, `POST /api/jobs`, `/api/mbtiles`, and `POST /api/mbtiles`
- added Kotlin/JS job websocket client wiring for `/ws/jobs/{jobId}`
- locked client-side relative URL passthrough and absolute URL generation helpers to match the current frontend contract
- updated the Compose entry screen to exercise Kotlin API bootstrap calls against the real backend routes
- Phase 4 - App State completed
- ported app-level state for jobs, server addresses, MBTiles tilesets, selected job, selected tileset, preview base, and live logs into Kotlin Compose state
- preserved the 5-second bootstrap polling cadence used by the current frontend for `/api/jobs`, `/api/mbtiles`, and `/api/server-info`
- preserved initial selection behavior by auto-selecting the first job and first MBTiles tileset only when nothing is currently selected
- preserved the selected-job flow of clearing logs, fetching the full job once for initial logs, then streaming follow-up `log` and `job` websocket events from `/ws/jobs/{jobId}`
- updated the Compose screen to render state-driven selection controls and live backend-backed status/log panels for Phase 4 verification
- Phase 5 - Base UI Panels completed
- ported the web UI shell into Compose with a responsive two-column layout matching the existing frontend structure
- ported read-only MBTiles catalog, job list, preview mode controls, job status, downloads, preview URL placeholder, and live logs panels into the Kotlin UI module
- preserved backend-facing artifact and preview URLs through Kotlin-generated links while intentionally deferring MapLibre rendering to Phase 7
- added Kotlin UI stylesheet resources so the served Compose app keeps the dark-panel visual structure used by the current frontend
- Phase 6 - Upload and Forms completed
- ported the HGT conversion form into Compose, including repeated multipart `hgt_files`, optional `base_mbtiles`, `bbox_mode`, manual bbox fields, zoom/tile-size options, `scheme`, and fixed `encoding=mapbox`
- ported the MBTiles upload form into Compose with backend-compatible `mbtiles` and `source_type` multipart fields
- preserved the current frontend's minimal client-side validation by checking only required file presence locally and leaving integer/manual-bbox validation to backend responses
- wired successful job creation and MBTiles upload responses back into shared app state so new jobs and tilesets are selected immediately in the migrated UI
- kept backend error bodies user-visible in the Kotlin UI so validation failures surface without changing the request/response contract
- Phase 7 - Map Interop completed
- added thin Kotlin/JS interop over the minimal `MapLibre GL JS` map and navigation-control APIs used by the current frontend
- ported terrain preview with backend-compatible Terrain-RGB, optional uploaded base overlay, hillshade, fit-bounds, pitch slider, and live zoom display
- ported uploaded MBTiles preview with raster vs `raster-dem` source handling, OSM overlay parity, fit-bounds, conditional pitch support, and live zoom display
- preserved current preview URL templates for `/api/jobs/{jobId}/terrain/{z}/{x}/{y}.png`, `/api/jobs/{jobId}/base/{z}/{x}/{y}`, and uploaded `tile_url_template` flows
- Phase 8 - Build Cutover completed
- Docker now builds frontend assets from `kotlin/terrain-web-ui/` with Gradle instead of Node/Vite
- Ktor still serves generated assets from `web/frontend/dist` through the existing `TERRAIN_WEB_FRONTEND_DIST` path
- Phase 9 - Verification completed for build and backend test coverage available in this workspace
- Phase 10 - Cleanup completed
- removed legacy React/Vite source, package, TypeScript, and Vite config files from `web/frontend/`
- updated README files, `AGENTS.md`, and `start-web.cmd` to use the Kotlin web UI workflow

## In Progress

- none

## Remaining

- none

## Decisions Locked

- Web UI source code must be Kotlin-only
- `MapLibre GL JS` is allowed only through Kotlin interop
- backend route shapes and JSON fields remain unchanged unless explicitly revised
- Ktor continues serving built frontend assets
- primary browser target is Kotlin/JS IR, not Kotlin/Wasm
- Compose Multiplatform HTML is the chosen UI layer
- production asset source directory is `kotlin/terrain-web-ui/build/dist/js/productionExecutable`
- backend-served frontend directory remains `web/frontend/dist`
- Phase 2 task names are `gradle -p kotlin/terrain-web-ui jsBrowserDistribution` and `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- Node/Vite/React are no longer part of the supported web UI workflow

## Blockers

- none

## Verification

- completed:
  - reviewed `docs/kotlin-web-ui-migration-plan.md`
  - saved findings in `docs/reviews/kotlin-web-ui-migration-plan-review.md`
  - applied required plan fixes from review
  - audited current frontend fetch, multipart, websocket, and MapLibre usage against Kotlin/JS browser capabilities
  - recorded the Phase 1 decision in `docs/kotlin-web-ui-tech-spike.md`
  - added and wired the `terrain-web-ui` Gradle module
  - verified the module builds with `gradle -p kotlin/terrain-web-ui jsBrowserDistribution`
  - verified backend compatibility path with `gradle -p kotlin/terrain-web-ui syncFrontendDist`
  - verified backend tests still pass with `gradle :terrain-web:test`
  - completed Phase 3 verification with `gradle -p kotlin/terrain-web-ui jsBrowserDistribution`
  - completed Phase 3 verification with `gradle -p kotlin/terrain-web-ui syncFrontendDist`
  - completed Phase 3 verification with `gradle :terrain-web:test`
  - completed Phase 4 verification with `gradle -p kotlin/terrain-web-ui jsBrowserDistribution`
  - completed Phase 4 verification with `gradle -p kotlin/terrain-web-ui syncFrontendDist`
  - completed Phase 4 verification with `gradle :terrain-web:test`
  - completed Phase 5 verification with `gradle -p kotlin/terrain-web-ui jsBrowserDistribution`
  - completed Phase 5 verification with `gradle -p kotlin/terrain-web-ui syncFrontendDist`
  - completed Phase 5 verification with `gradle :terrain-web:test`
- completed Phase 6 verification with `gradle -p kotlin/terrain-web-ui jsBrowserDistribution`
- completed Phase 6 verification with `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- completed Phase 6 verification with `gradle :terrain-web:test`
- completed Phase 7 verification with `gradle -p kotlin/terrain-web-ui jsBrowserDistribution`
- completed Phase 7 verification with `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- completed Phase 7 verification with `gradle :terrain-web:test`
- completed Phase 8/10 cleanup wiring with `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- completed backend regression verification with `gradle :terrain-web:test`
- pending:
  - Docker/Compose config validation in this workspace because `docker` is not installed
  - manual browser smoke test for map-backed preview work on a running backend

## Files Added or Changed in This Phase

- `docs/kotlin-web-ui-migration-plan.md`
- `docs/kotlin-web-ui-migration-status.md`
- `docs/kotlin-web-ui-tech-spike.md`
- `docs/kotlin-web-ui-session-runbook.md`
- `docs/reviews/kotlin-web-ui-migration-plan-review.md`
- `build.gradle.kts`
- `settings.gradle.kts`
- `kotlin/terrain-web-ui/build.gradle.kts`
- `kotlin/terrain-web-ui/settings.gradle.kts`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Api.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/AppState.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/MapLibre.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Models.kt`
- `kotlin/terrain-web-ui/src/jsMain/resources/app.css`
- `kotlin/terrain-web-ui/src/jsMain/resources/index.html`
- `web/frontend/dist/`
- `web/Dockerfile`
- `README.md`
- `web/README.md`
- `AGENTS.md`
- `start-web.cmd`
- removed legacy `web/frontend/` React/Vite source and config files
- `.opencode/agents/kotlin-compose-web-ui.md`
- `.opencode/commands/plan-kotlin-web-ui-migration.md`
- `.opencode/commands/build-kotlin-web-ui.md`
- `.opencode/commands/continue-kotlin-web-ui-migration.md`
- `.opencode/commands/review-kotlin-web-ui-migration-plan.md`
- `.opencode/commands/review-kotlin-web-ui-implementation.md`
- `.opencode/commands/review-kotlin-web-ui-docs.md`

## Next Session Start Here

1. run `gradle -p kotlin/terrain-web-ui syncFrontendDist` after Kotlin UI changes
2. run `gradle :terrain-web:test` after backend or served-asset changes
3. smoke-test `http://127.0.0.1:8080/` with a running backend before release
