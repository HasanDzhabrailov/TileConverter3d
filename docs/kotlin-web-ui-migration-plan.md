# Kotlin Web UI Migration Plan

Status: complete - Kotlin web UI cutover done

This file is the canonical saved plan used to migrate the former React/Vite frontend to a Kotlin-only Web UI.

## Goal

Replace `web/frontend/` with a Kotlin-only Web UI built with Compose Multiplatform while preserving the existing `terrain-web` backend contract, runtime workflow, and Ktor-served frontend behavior.

## Locked Decisions

- the supported Web UI implementation must be Kotlin-only
- `MapLibre GL JS` is allowed only through Kotlin interop
- backend route shapes and JSON fields remain unchanged unless explicitly revised in this plan
- Ktor continues to serve built frontend assets through `TERRAIN_WEB_FRONTEND_DIST`
- migration should be phased and verified incrementally, not as a single rewrite

## Locked External Behavior

Later sessions must preserve the following unless this plan is explicitly updated first:

- current backend route shapes under `/api` and `/ws`
- current JSON field names, nullability expectations, and relative vs absolute URL behavior
- current error payload shape and backend-visible validation semantics
- current WebSocket event categories, payload structure, and initial-plus-update flow for job events/log events
- current upload field names:
  - `hgt_files`
  - `base_mbtiles`
  - `mbtiles`
  - `source_type`
- current job artifact URL shapes and download behavior
- current uploaded MBTiles behavior, including `source_type` auto-detection and `/style-mobile`
- current preview behavior for terrain jobs and uploaded MBTiles
- current Terrain-RGB assumptions used by the preview flow:
  - `encoding=mapbox`
  - `tileSize=256` by default unless the job explicitly changes it
  - relative `/api/jobs/...` URLs in backend-produced style and TileJSON documents

## Build And Runtime Constraints

- no supported runtime path may depend on React, TypeScript, or Vite after cutover
- no supported runtime path may require Node.js after cutover
- temporary migration steps may coexist with the legacy frontend until cutover is complete
- the final supported frontend build must be driven by Gradle/Kotlin tooling and produce assets consumable by `terrain-web`
- the final asset output path must be wired through `TERRAIN_WEB_FRONTEND_DIST` so Ktor static serving behavior stays compatible
- Docker and Compose must continue to produce a single Kotlin backend container that serves built frontend assets

## Current Baseline

- legacy UI sources lived in `web/frontend/` before Phase 10 cleanup
- current backend lives in `kotlin/terrain-web/`
- current frontend contract depends on:
  - `GET /api/server-info`
  - `GET /api/jobs`
  - `GET /api/jobs/{jobId}`
  - `POST /api/jobs`
  - `GET /api/mbtiles`
  - `POST /api/mbtiles`
  - `WS /ws/jobs/{jobId}`
  - job artifact, terrain tile, base tile, tilejson, style, style-mobile endpoints
- current frontend behavior depends on:
  - polling plus WebSocket updates for job state
  - multi-file HGT uploads and optional `base.mbtiles`
  - copied URL actions for tiles, TileJSON, style, and mobile style
  - map previews for completed jobs and uploaded MBTiles
  - preview base selection and terrain/base overlay behavior
- Docker/Compose builds Kotlin web UI assets and lets Ktor serve them

## Target Module Structure

```text
kotlin/
  terrain-core/
  terrain-web/
  terrain-web-ui/
web/
  Dockerfile
  docker-compose.yml
docs/
  kotlin-web-ui-migration-plan.md
  kotlin-web-ui-migration-status.md
  kotlin-web-ui-session-runbook.md
  reviews/
```

## Migration Phases

### Phase 0: Baseline Capture

- capture the current frontend-visible contract before implementation starts
- save the exact migration guardrails in this plan and in the status file
- identify any existing backend tests, saved contract fixtures, or frontend behaviors that can be reused as parity references

Acceptance criteria:

- the preserved external behavior is listed concretely enough for later sessions
- upload, WebSocket, map preview, and artifact URL expectations are explicitly locked
- the next session can start implementation without requiring chat context

### Phase 1: Tech Spike

Validate the chosen Kotlin web target against critical browser integrations:

- fetch
- multipart upload
- WebSocket
- MapLibre mount/unmount

The spike must also decide and document:

- final web target/runtime choice
- JS interop approach for browser APIs and MapLibre
- whether any browser limitation changes phase ordering or build packaging

Acceptance criteria:

- browser integration is proven feasible in Kotlin
- any target-specific constraints are recorded
- the final web target choice is documented

### Phase 2: Module Setup

- add `terrain-web-ui` module
- configure Compose Multiplatform and web target
- define production asset output path compatible with Ktor serving
- define local development workflow
- define the exact frontend distribution directory used by `TERRAIN_WEB_FRONTEND_DIST`
- define the Gradle task(s) that produce production assets

Acceptance criteria:

- the new module builds
- web assets can be produced in a path usable by backend serving
- Docker/Compose cutover path is concrete enough to implement later without changing the contract

### Phase 3: Models and API

- port frontend types to Kotlin serializable models
- implement Kotlin HTTP client wrappers
- implement websocket event handling
- preserve field names and URL semantics from the current UI/backend flow
- lock handling for relative artifact URLs vs absolute public URLs

Acceptance criteria:

- Kotlin code can load jobs, tilesets, and server-info
- Kotlin code can subscribe to job WebSocket events
- no backend contract drift is introduced
- uploaded MBTiles metadata/style/mobile-style flows remain reachable through the migrated client layer

### Phase 4: App State

- port app orchestration from the current frontend
- implement polling, selections, preview mode, and logs state
- preserve initial selection behavior and current polling cadence unless the plan is updated
- preserve the initial job event plus subsequent WebSocket updates flow used by the UI

Acceptance criteria:

- the root screen operates against real backend data
- polling and websocket behavior remain compatible
- migrated state flow does not lose job/log updates that the current UI surfaces

### Phase 5: Base UI Panels

- port shell/layout
- port job list, status, download, logs, and preview mode controls

Acceptance criteria:

- read-only workflow is functional in Kotlin UI

### Phase 6: Upload and Forms

- port HGT upload form
- port optional base MBTiles upload
- port MBTiles server panel
- preserve form field names and validation behavior
- preserve repeated multipart parts for multiple `hgt_files`
- preserve backend-visible filename handling and accepted file combinations
- preserve user-visible handling of backend validation failures

Acceptance criteria:

- users can create jobs and upload MBTiles from Kotlin UI
- multipart requests are backend-compatible for single-file, multi-file, ZIP, and optional base MBTiles flows

### Phase 7: Map Interop

- port terrain preview
- port uploaded MBTiles preview
- wrap only the minimal `MapLibre GL JS` API needed
- preserve current style/TileJSON URL usage and job/base tiles preview semantics
- preserve current preview support for uploaded MBTiles `style` and `style-mobile` related flows where used by the UI

Acceptance criteria:

- both previews work with current backend outputs and URLs
- terrain and uploaded MBTiles previews preserve current source types, URL templates, and overlay expectations

### Phase 8: Build Cutover

- integrate new UI build into Docker/Compose
- preserve Ktor static serving behavior
- switch production path to Kotlin UI assets
- remove the legacy frontend from the supported runtime path only after parity verification completes
- document the exact Dockerfile and Compose changes required for final cutover

Acceptance criteria:

- Compose stack runs Kotlin backend plus Kotlin UI assets
- no Node/Vite runtime path remains required
- Ktor serves the migrated frontend with the same fallback/static behavior expected today

### Phase 9: Verification

- run backend tests
- run frontend build verification
- smoke test the full workflow
- run parity-oriented checks against the current workflow, not only smoke tests
- verify browser-facing upload, WebSocket, map preview, copied URL, and static asset serving behavior
- validate the migration path on Windows and keep Linux/macOS commands and paths compatible

Acceptance criteria:

- upload, conversion, logs, downloads, and previews all work
- side-by-side behavior checks do not reveal contract drift in the supported workflow

### Phase 10: Cleanup

- remove legacy React/Vite frontend
- remove obsolete Node/Vite config from the supported path
- update docs

Acceptance criteria:

- the supported web UI stack is Kotlin-only

## High-Risk Areas

- browser file and `FormData` interop
- multiple file upload behavior
- WebSocket lifecycle and event timing
- `MapLibre GL JS` interop
- production asset packaging and Ktor static serving
- cutover sequencing without breaking the Compose stack
- cross-platform build/run differences across Windows, Linux, and macOS

## Verification Checklist

- backend tests still pass
- Kotlin web UI builds successfully
- Ktor serves built frontend assets correctly
- copied URLs still match current backend URL conventions
- upload requests remain backend-compatible for:
  - single HGT
  - multiple HGT files
  - ZIP upload
  - optional `base.mbtiles`
  - MBTiles catalog upload with `source_type`
- HGT upload works
- optional base MBTiles upload works
- MBTiles catalog upload works
- job status and live logs still work
- WebSocket initial event and subsequent updates still drive the UI correctly
- terrain preview works
- uploaded MBTiles preview works
- download links work
- copied URLs still point to valid backend resources
- `/style-mobile` remains reachable for uploaded MBTiles flows
- backend-served built frontend still works with direct browser navigation and static fallback behavior

## Cross-Platform And Browser Checks

- keep implementation and docs compatible with Windows, Linux, and macOS
- validate local build/run commands at least on the current Windows workspace during migration
- avoid introducing shell assumptions that break Linux/macOS or Windows paths
- record any browser-specific limitation discovered during the tech spike in the status file before implementation continues

## Documentation Deliverables

- `docs/kotlin-web-ui-migration-status.md`
- `docs/kotlin-web-ui-session-runbook.md`
- `README.md`
- `web/README.md`
- review files under `docs/reviews/`

## Cutover Criteria

- Kotlin UI covers all supported frontend workflows
- verification checklist passes
- saved review of the migration plan is complete and any required fixes are applied
- implementation review before final cleanup does not contain unresolved release-blocking findings
- docs are updated
- status file clearly marks migration as complete
