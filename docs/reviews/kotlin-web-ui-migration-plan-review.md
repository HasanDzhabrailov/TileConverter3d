# Kotlin Web UI Migration Plan Review

Date: 2026-04-30
Reviewer: OpenCode + kotlin-parity-reviewer

## Findings

1. High - `docs/kotlin-web-ui-migration-plan.md` was too loose as a saved source-of-truth plan for later sessions. It listed major endpoints and phases, but it did not explicitly lock the frontend-visible contract: JSON field names, relative vs absolute URL behavior, WebSocket event flow, upload field names, uploaded MBTiles details like `source_type` auto-detection and `/style-mobile`, or Terrain-RGB preview assumptions.
2. High - upload parity requirements were under-specified. The original plan did not explicitly preserve repeated multipart parts for `hgt_files`, accepted upload combinations, or backend-visible validation/error behavior.
3. High - MapLibre preview migration was too vague. The original plan did not clearly preserve job preview semantics, backend-produced style/TileJSON URL behavior, or uploaded MBTiles preview expectations.
4. Medium - build and cutover detail was too general. The original plan did not define the exact responsibility for production asset generation, `TERRAIN_WEB_FRONTEND_DIST` compatibility, or the final Gradle/Kotlin-driven frontend build path.
5. Medium - verification was smoke-test oriented rather than parity oriented. It needed explicit checks for upload contract behavior, WebSocket flow, copied URLs, `/style-mobile`, and backend static asset serving behavior.
6. Medium - cross-platform and browser risks needed to be called out explicitly so later sessions would not assume a single-platform happy path.

## Open Questions

- The plan now locks browser-visible behavior more explicitly, but the exact final web target/runtime choice still depends on Phase 1 tech spike results.
- The exact Gradle task names and final production asset directory for `terrain-web-ui` remain to be chosen in Phase 2.
- Browser coverage scope should be recorded in the status file once the tech spike identifies any runtime-specific limitation.

## Verdict

- ready with fixes applied

## Follow-Up Applied

- Added `Locked External Behavior` section
- Added `Build And Runtime Constraints` section
- Added `Phase 0: Baseline Capture`
- Strengthened Phase 1 through Phase 9 requirements and acceptance criteria
- Expanded verification and cross-platform checks
- Tightened cutover criteria for later sessions
