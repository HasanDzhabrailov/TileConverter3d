# Terrain UI Redesign Plan Review

Status: final

## Verdict

- go with fixes

## Findings

1. Severity: high
   File: `docs/terrain-ui-redesign-plan.md:152`
   Issue: Dynamic style endpoints are listed, but the plan does not define the style contract deeply enough for compatible backend/frontend/mobile implementation. It needs to specify whether job and uploaded MBTiles styles share source/layer IDs, how mobile style rewrites every tile/style/TileJSON URL to the LAN address, how `base=none` affects layer order, and how `raster-dem` MBTiles differs from raster MBTiles in generated style JSON.

2. Severity: high
   File: `docs/terrain-ui-redesign-plan.md:231`
   Issue: Real-time MBTiles upload progress is required, but the plan does not define the transport and payload contract. It says to use browser progress and backend job/progress events, but does not specify whether backend progress uses WebSocket, SSE, polling, existing job events, a new upload ID, cancellation IDs, or response schemas for stage transitions.

3. Severity: medium
   File: `docs/terrain-ui-redesign-plan.md:98`
   Issue: Backend-persisted base sources are under-specified for implementation. The plan should define the SQLite table/migration ownership, duplicate-name or duplicate-URL behavior, generated ID stability, `minZoom` or bounds support if needed by MapLibre, attribution propagation into generated styles, and whether URL templates may include subdomains, query tokens, retina suffixes, or local relative paths.

4. Severity: medium
   File: `docs/terrain-ui-redesign-plan.md:186`
   Issue: 2D versus 3D Preview behavior remains ambiguous for uploaded raster MBTiles. The plan says 2D always uses the selected base source, removes `Uploaded base`, and also allows `Открыть в 2D` for raster tilesets if existing behavior supports it. That leaves unclear whether a raster MBTiles can be previewed in the main Preview as a temporary selected map source, only opened from its card, or only served/listed outside Preview.

5. Severity: medium
   File: `docs/terrain-ui-redesign-plan.md:266`
   Issue: Mobile copy links require the active LAN/mobile address, but the source of that address is not specified. The plan should identify the backend field or endpoint that exposes the mobile base URL, fallback rules when several interfaces exist, how Docker/host networking affects it, and whether copied `style` and `style-mobile` links must URL-encode custom `base` IDs.

6. Severity: medium
   File: `docs/terrain-ui-redesign-plan.md:303`
   Issue: Cache clearing defines `DELETE /api/system/cache` but not the request/response schema or safety semantics. The next plan pass should define checkbox group keys, dry-run/confirmation data, what happens to running jobs, how failed partial MBTiles uploads are represented, and how frontend state should reconcile after partial deletion errors.

7. Severity: low
   File: `docs/terrain-ui-redesign-plan.md:386`
   Issue: Verification covers high-level manual checks but misses contract-level checks that will catch the riskiest regressions. Add backend tests for base source CRUD/persistence, style JSON layer/source output for each `base` mode, mobile absolute URL rewriting, MBTiles progress events, cache delete request validation, and frontend build checks for Russian-only visible copy where practical.

## Required Fixes Before Next Stage

- Update the plan with explicit desktop/mobile style JSON contract details, including layer/source naming, LAN URL rewriting, `base=none`, and job versus MBTiles behavior.
- Define the MBTiles upload progress transport, IDs, event payloads, cancellation behavior, and error/retry states.
- Expand the base source backend contract with SQLite persistence/migration details, attribution handling, URL-template capabilities, and validation edge cases.
- Resolve the uploaded raster MBTiles 2D Preview ambiguity after removing `Uploaded base`.
- Define the active LAN/mobile address source and copied-link URL construction rules.
- Define the cache clearing request/response schema and partial failure behavior.
- Add contract-focused automated verification steps to the plan.

## Safe To Start Next Command?

- no
- next command: `/plan-terrain-ui-redesign`

## Notes

- The saved plan covers all requested product requirements at a high level.
- The blockers are contract precision issues, not product-scope omissions.
- Implementation should not start with `/build-base-sources-backend` until the plan is refreshed with the required contract details.
