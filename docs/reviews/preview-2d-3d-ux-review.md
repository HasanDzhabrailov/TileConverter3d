# Preview 2D/3D UX Review

Date: 2026-05-07

Scope:

- `AGENTS.md`
- `docs/terrain-ui-redesign-plan.md`
- `docs/terrain-ui-redesign-status.md`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/AppState.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/MapLibre.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Models.kt`
- `kotlin/terrain-web-ui/src/jsMain/resources/app.css`
- `kotlin/terrain-web-ui/src/jsTest/kotlin/com/terrainconverter/web/ui/PreviewStyleTest.kt`

## Findings

1. High: `Открыть в 3D` for a DEM MBTiles can still render the selected completed job instead of the chosen DEM tileset.

   In `AppState.openTilesetIn3D`, the UI records only `selectedTilesetId` and switches `previewMode` to `THREE_D`, but it does not clear or otherwise de-prioritize `selectedJobId` (`AppState.kt:119-123`). `MapPreviewPanel` then treats any completed selected job as the active terrain source and only uses the DEM tileset when there is no completed selected job (`Main.kt:582-584`, `Main.kt:714-720`). If a completed conversion is selected, clicking `Открыть в 3D` on a `raster-dem` MBTiles card shows the job terrain, not the MBTiles source the user explicitly chose. This violates the plan's source semantics for uploaded DEM MBTiles and makes the card action misleading.

   Recommendation: Track the active terrain preview source explicitly, for example `Job(jobId)` versus `MbtilesDem(tilesetId)`, or clear/suspend `selectedJobId` for preview purposes when opening DEM MBTiles in 3D. Keep job selection for status/log panels if needed, but do not let it override an explicit DEM preview action.

2. High: Selected base source is not propagated to style URLs or the backend dynamic style endpoints.

   The redesign requires selected base map sources to be reflected in style URLs via `?base={sourceId}` (`docs/terrain-ui-redesign-plan.md:40`, `docs/terrain-ui-redesign-plan.md:152-176`, `docs/terrain-ui-redesign-plan.md:284-289`). The frontend preview implementation instead has a local `PreviewBase` enum with only `OSM` and `NONE` (`AppState.kt:14-17`) and builds MapLibre styles inline (`Main.kt:1050-1136`). MBTiles style copy links use `tileset.styleUrl` and `tileset.mobileStyleUrl` directly without appending the active base (`Main.kt:183-190`, `Main.kt:227-231`, `Main.kt:253-257`). Job style links similarly display artifact URLs without selected base (`Main.kt:807-820`).

   This means copied/opened styles can silently use the backend default base instead of the user's selected base, and custom/global base sources from the prior backend stage are not reachable from this UI. It also leaves the preview base selection disconnected from the dynamic style API contract.

   Recommendation: Load the backend base-source list, store the selected source ID as global UI state, and use it consistently when building preview styles and user-facing style links. For backend style endpoints, append `?base=<selectedSourceId>` to job and MBTiles desktop/mobile style URLs.

3. Medium: 3D availability is derived from the selected tileset, but the UI auto-selects the first tileset on bootstrap.

   `applyBootstrap` assigns `selectedTilesetId` to the first returned tileset when none is selected (`AppState.kt:64-66`). Because `MapPreviewPanel` enables 3D when `selectedTileset` is `raster-dem` (`Main.kt:582-585`), merely opening the app with an existing DEM tileset first in the list can enable the 3D toggle before the user explicitly chooses that DEM source in the current session. The plan says uploaded `raster-dem` MBTiles should show that 3D is available but should switch to 3D only when the user selects it (`docs/terrain-ui-redesign-plan.md:74-79`) and should not unexpectedly steal focus from an active job preview (`docs/terrain-ui-redesign-plan.md:37`).

   Recommendation: Avoid auto-selecting a tileset for preview source purposes on bootstrap, or separate catalog selection from terrain preview source. Keep the `3D доступен` badge/action on DEM cards and enable 3D only after explicit DEM selection or a completed conversion auto-switch.

4. Medium: The frontend test coverage does not cover the implemented UX rules.

   The only preview test checks terrain job style scheme preservation (`PreviewStyleTest.kt:7-19`). There are no tests for 2D default mode, disabled 3D state, conversion-complete auto-switch, raster versus `raster-dem` MBTiles gating, DEM MBTiles source precedence, or appending selected base IDs to style URLs. These are the highest-risk behaviors in this stage and at least one source-precedence bug is present.

   Recommendation: Add focused tests around `AppState` transitions and style URL helper behavior. If Compose DOM tests are too heavy, keep most coverage in pure state/helper functions.

## Verified Behavior

- 2D preview starts as `PreviewMode.TWO_D` with `PreviewBase.OSM` (`AppState.kt:31-32`).
- `Uploaded base` is absent from the preview mode/base controls; the current controls are `2D карта`, `3D рельеф`, `OpenStreetMap`, and `Без подложки` (`Main.kt:601-643`).
- 3D is disabled when there is no completed selected job and no selected DEM MBTiles (`Main.kt:582-585`, `Main.kt:614-627`).
- Conversion completion auto-selects the completed job and switches preview to 3D when a job transitions into `COMPLETED` through polling or WebSocket merge (`AppState.kt:67-69`, `AppState.kt:100-105`, `AppState.kt:125-130`).
- Raster MBTiles get `Открыть в 2D`; DEM MBTiles get `3D доступен` and `Открыть в 3D` (`Main.kt:160-181`).
- The responsive CSS collapses the two-column layout and preview controls at narrow widths (`app.css:221-244`).

## Residual Risks

- Manual browser verification was not performed as part of this review.
- `gradle -p kotlin/terrain-web-ui syncFrontendDist` was reported passing in the build stage, but this review did not rerun it.
- Large portions of the UI remain English-only, but this review focused on the requested preview UX areas rather than the full Russian-copy checklist.

## Re-Review After Fixes

Date: 2026-05-07

Reviewed fixed files:

- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/AppState.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Models.kt`
- `kotlin/terrain-web-ui/src/jsTest/kotlin/com/terrainconverter/web/ui/PreviewStyleTest.kt`

### Remaining Finding

1. Medium: Selecting a completed job from the job list after opening a DEM MBTiles in 3D does not make that job the active 3D preview source.

   The fix added explicit `TerrainPreviewSource.Job` and `TerrainPreviewSource.MbtilesDem`, which closes the original DEM-overridden-by-job bug. However, job card clicks still only update `selectedJobId` (`Main.kt:560-563`). If the current `terrainPreviewSource` is `MbtilesDem`, `MapPreviewPanel` ignores the selected completed job because fallback to `selectedJob` only runs when `terrainPreviewSource == null` (`Main.kt:582-588`). The result is that a user can click a completed job in `Задачи` and still see the previously opened DEM MBTiles in 3D. If the DEM tileset later disappears, 3D can also become unavailable even while a completed selected job exists.

   Recommendation: Add a state method for job-card selection, for example `selectJob(job)`, that updates `selectedJobId` and, when `job.status == COMPLETED`, sets `terrainPreviewSource = TerrainPreviewSource.Job(job.id)` or clears stale explicit DEM selection. This preserves explicit DEM precedence while making an explicit completed-job click behave as a preview-source selection.

### Closed Findings

- Closed: DEM MBTiles `Открыть в 3D` no longer gets overridden by an already selected completed job. `openTilesetIn3D` now sets `TerrainPreviewSource.MbtilesDem`, and preview resolution respects that explicit source (`AppState.kt:124-128`, `Main.kt:582-588`).
- Closed: Base sources now load from `/api/base-sources`, are stored in `selectedBaseSourceId`, and are used for preview base layers (`Api.kt:27-36`, `AppState.kt:24-50`, `Main.kt:636-648`, `Main.kt:1065-1141`).
- Closed: Job and MBTiles style links now append `?base={selectedSourceId}` for dynamic style endpoints (`Main.kt:183-188`, `Main.kt:225-231`, `Main.kt:251-257`, `Main.kt:813-826`).
- Closed: Existing DEM tilesets are no longer auto-selected on bootstrap; `selectedTilesetId` is not assigned in `applyBootstrap` (`AppState.kt:52-75`).
- Closed: Focused JS tests now cover conversion-complete preview switching, DEM source selection, bootstrap non-selection, base query generation, and terrain scheme preservation (`PreviewStyleTest.kt:7-68`).

### Verification Notes

- `gradle -p kotlin/terrain-web-ui jsNodeTest` was reported passing after the fixes.
- `gradle -p kotlin/terrain-web-ui syncFrontendDist` was reported passing after the fixes.
- This re-review did not rerun Gradle or perform manual browser testing.

## Final Re-Review After Edge-Case Fix

Date: 2026-05-07

Result: no remaining findings in the reviewed preview 2D/3D UX scope.

Closed remaining finding:

- Completed job card selection now calls `AppState.selectJob(job)`, and completed jobs explicitly set `terrainPreviewSource = TerrainPreviewSource.Job(job.id)` (`AppState.kt:117-125`, `Main.kt:560-563`). This closes the issue where a previously opened DEM MBTiles source stayed active after clicking a completed job.
- Regression coverage was added for selecting a completed job after DEM preview (`PreviewStyleTest.kt:46-60`).

Verification:

- Passed: `gradle -p kotlin/terrain-web-ui jsNodeTest`
- Passed: `gradle -p kotlin/terrain-web-ui syncFrontendDist`

Residual risks:

- Manual browser verification was not performed.
- Russian-only copy remains a broader redesign item outside this focused preview fix.

## Follow-Up Review

Date: 2026-05-07

Result: no findings in the reviewed preview 2D/3D UX scope.

Reviewed behavior:

- Completed job card clicks now route through `AppState.selectJob(job)` and make completed jobs the active terrain preview source.
- Explicit DEM MBTiles preview remains explicit when the user clicks `Открыть в 3D`.
- Bootstrap still avoids auto-selecting DEM MBTiles as the active preview source.
- Style links still append the selected base source for dynamic style endpoints.
- Regression test coverage includes the completed-job-after-DEM path.

Verification not rerun during this follow-up review. Prior recorded checks remain:

- Passed: `gradle -p kotlin/terrain-web-ui jsNodeTest`
- Passed: `gradle -p kotlin/terrain-web-ui syncFrontendDist`
