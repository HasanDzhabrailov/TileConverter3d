# Kotlin Web UI Tech Spike

Date: 2026-04-30
Status: complete

This file records the Phase 1 tech-spike decision for the Kotlin-only Web UI migration.

## Verdict

Use a Kotlin/JS browser target with Compose Multiplatform HTML UI.

Do not use Kotlin/Wasm as the primary target for this migration.

## Chosen Target

- runtime: Kotlin/JS IR in the browser
- UI layer: Compose Multiplatform HTML DOM API
- JSON models: `kotlinx.serialization`
- browser integration: native browser APIs from Kotlin/JS
- map integration: `MapLibre GL JS` via thin Kotlin external declarations

## Why This Target

- the current frontend relies on standard browser primitives, not React-specific browser features
- `fetch`, `FormData`, `WebSocket`, `File`, `Blob`, `navigator.clipboard`, and DOM events map directly to Kotlin/JS browser APIs
- `MapLibre GL JS` is a JavaScript library, so Kotlin/JS interop is the most direct and lowest-risk bridge
- Compose HTML is sufficient for the current panel-heavy UI without forcing a canvas-first UI runtime
- Ktor can keep serving static built assets because the browser target still emits JS/CSS/HTML assets

## Rejected Primary Target

Kotlin/Wasm was rejected for this migration phase because it adds avoidable risk in the two highest-risk areas:

- JavaScript library interop is more direct and predictable on Kotlin/JS than on a Wasm-first path
- the current migration needs low-friction browser file upload and MapLibre integration before UI redesign is warranted

Wasm can be revisited later only after parity is complete and the JS interop surface is stable.

## Browser Integration Findings

### Fetch

- current usage is simple request/response fetch against relative `/api` endpoints
- Kotlin/JS can call `window.fetch` directly
- use browser `fetch` rather than adding an HTTP abstraction that might alter request details

Decision:

- implement API calls with browser `fetch`
- parse JSON with `kotlinx.serialization`
- keep relative artifact and API URL handling aligned with the current frontend

### Multipart Upload

- current job creation depends on repeated `hgt_files` parts plus optional `base_mbtiles`
- current MBTiles upload depends on `mbtiles` plus `source_type`
- browser `FormData` in Kotlin/JS preserves the required repeated-part behavior

Decision:

- build uploads with native `FormData`
- append each `hgt_files` entry individually to preserve backend-visible multipart semantics
- do not hide uploads behind a serializer or generic client layer

### WebSocket

- current UI opens `ws://` or `wss://` based on the current page protocol and host
- event handling is simple JSON message parsing for `job` and `log` events

Decision:

- use browser `WebSocket` directly from Kotlin/JS
- keep the initial job event plus follow-up updates contract unchanged
- keep socket URL derivation based on `window.location`

### MapLibre

- current previews construct style objects in the frontend and mount/unmount a MapLibre map instance on selection changes
- required features are narrow: `Map`, `NavigationControl`, `fitBounds`, `setPitch`, `getZoom`, `on`, `remove`, and plain style/source/layer objects

Decision:

- keep `MapLibre GL JS` as the rendering engine
- wrap only the minimal API surface with Kotlin external declarations
- keep style documents as plain JS object graphs built from Kotlin data
- manage map lifecycle manually from a dedicated preview wrapper instead of introducing a broader abstraction

## Packaging Implications

- the final frontend can still be served by `terrain-web` through `TERRAIN_WEB_FRONTEND_DIST`
- the Kotlin UI module should produce static browser assets into its build output
- Phase 2 must lock the exact Gradle task names and the exact distribution directory handed to Ktor and Docker

## Phase 2 Requirements Derived From This Spike

- add `kotlin/terrain-web-ui/` as a Kotlin/JS browser module
- enable Compose Multiplatform for HTML-based UI rendering
- add Gradle-managed JS dependency wiring for `maplibre-gl`
- define how `maplibre-gl.css` is included in the generated distribution
- lock the production asset directory used by `TERRAIN_WEB_FRONTEND_DIST`
- document the local development loop for backend plus Kotlin browser assets

## Guardrails For Implementation

- preserve current backend route shapes and JSON field names
- preserve repeated multipart field behavior for `hgt_files`
- preserve relative `/api/jobs/...` URL handling for job-generated artifacts
- preserve copied URL behavior for server addresses, TileJSON, style, and mobile style links
- keep the initial selection, polling, and websocket flow compatible with the current UI
- keep MapLibre interop thin and local to preview code

## Verification Performed In This Spike

- audited the current React/Vite frontend API layer in `web/frontend/src/api.ts`
- audited current JSON model shapes in `web/frontend/src/types.ts`
- audited current upload behavior in `web/frontend/src/components/ConvertForm.tsx`, `UploadPanel.tsx`, and `MbtilesServerPanel.tsx`
- audited current MapLibre usage in `web/frontend/src/components/MapPreview.tsx` and `MbtilesPreview.tsx`
- confirmed no Phase 1 requirement depends on React-specific behavior that blocks Kotlin/JS browser migration

## Open Constraints

- exact Compose and Kotlin plugin versions must be chosen to fit the repo's existing Kotlin `1.9.23` baseline or be upgraded deliberately in Phase 2
- exact Gradle production asset task names remain a Phase 2 decision
- CSS handling for `maplibre-gl` must be locked in Phase 2 so Docker and Ktor serve the same output consistently
