# MBTiles Upload Progress Review

Date: 2026-05-07

Scope reviewed:

- `AGENTS.md`
- `docs/terrain-ui-redesign-plan.md`
- `docs/terrain-ui-redesign-status.md`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Api.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt`
- `kotlin/terrain-web-ui/src/jsMain/resources/app.css`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/TerrainWebServer.kt`
- `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/MultipartParsing.kt`
- relevant backend parity/API tests

## Findings

1. High: Server-side startup progress is not implemented, so the UI cannot show the required validation/metadata/type-detection/preparation stages before readiness.

   The redesign plan requires browser upload progress plus backend job/progress events for `validating`, `reading_metadata`, `detecting_type`, and `preparing_server`. The current frontend only receives XHR upload percentages in `ApiClient.uploadMbtilesTileset` and then displays a static `Upload complete. Preparing tileset...` message while the existing synchronous `POST /api/mbtiles` moves the file, opens SQLite metadata, detects source type, builds links, and responds. There is no backend progress endpoint, job, or event stream for MBTiles startup in `TerrainWebServer.kt:320-348`, and no UI state for stages in `Main.kt:343-358`. Large MBTiles can still leave users waiting on a silent server-processing request after upload reaches 100%.

   References: `docs/terrain-ui-redesign-plan.md:231-264`, `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/TerrainWebServer.kt:320-348`, `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Api.kt:87-124`, `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:343-358`.

2. Medium: Cancellation and lifecycle cleanup are not wired to the underlying XHR.

   `sendFormWithUploadProgress` creates a local `XMLHttpRequest`, but it is not returned or connected to coroutine cancellation/disposal, and there is no cancel action in the UI. If the upload form leaves composition, the coroutine scope may cancel, but the browser request is not aborted and the callbacks can still run. The plan says to show a cancel control when supported, or hide/disable cancellation with a Russian explanation when it is not supported end to end. The current UI does neither, so users cannot stop a mistaken large upload and there is no clear lifecycle cleanup path.

   References: `docs/terrain-ui-redesign-plan.md:248-264`, `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Api.kt:92-123`, `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:275-309`.

3. Medium: Error handling clears the progress card and surfaces raw/non-localized messages instead of preserving a failed upload state with retry guidance.

   On failure, `Main.kt` sets `error` and then immediately clears `uploadProgress`, removing the upload/progress card context. XHR failures reject with raw response text or English transport messages, so backend JSON such as `{"error":"Upload a .mbtiles file"}` can be displayed directly. The plan requires the failed card to remain visible with a Russian error message and retry guidance, and review focus includes error behavior and UI clarity before readiness.

   References: `docs/terrain-ui-redesign-plan.md:231-264`, `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Api.kt:104-120`, `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:304-309`, `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:338-358`.

4. Low: Large-file progress lacks byte counts, speed, and file context, reducing clarity during long uploads.

   The browser progress event has `loaded` and `total`, but the implementation collapses it to an integer percentage. The requested UX includes file name, transferred bytes, total bytes, and transfer speed. This is less severe than the missing server stages because percent progress is still present during transfer, but it falls short for large-file behavior and makes stalled uploads harder to diagnose.

   References: `docs/terrain-ui-redesign-plan.md:246-255`, `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Api.kt:95-101`, `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:343-358`.

## Compatibility Notes

- Existing `/api/mbtiles` multipart field names remain compatible: `mbtiles` and `source_type` are unchanged.
- Existing `source_type` values and auto-detection are unchanged in the reviewed code.
- Existing `/style`, `/style-mobile`, `/tilejson`, and tile-serving routes are unchanged.

## Testing Gaps

- No frontend tests cover upload progress rendering, the 100% to server-readiness transition, XHR error rendering, or cancellation/disposal behavior.
- Existing backend parity/API tests cover `/api/mbtiles` compatibility, but there are no tests for MBTiles progress events because no backend progress surface exists yet.
