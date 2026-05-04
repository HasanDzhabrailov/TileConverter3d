# Kotlin Web UI Docs Review

Date: 2026-04-30
Reviewer: OpenCode (kotlin-parity-reviewer agent)

## Scope

Reviewed documentation for the Kotlin Web UI migration:

- `docs/kotlin-web-ui-migration-plan.md`
- `docs/kotlin-web-ui-migration-status.md`
- `docs/kotlin-web-ui-session-runbook.md`
- `docs/kotlin-web-ui-tech-spike.md`
- `README.md`
- `web/README.md`

## Findings

### High Severity

1. **Phase reference mismatch in runbook** - The `kotlin-web-ui-session-runbook.md` lists Phase 9 as "Verification" and Phase 10 as "Cleanup", but these are not aligned with the main migration plan's phase numbering. This could cause confusion when cross-referencing between documents.

2. **Missing Phase 8 documentation** - The migration status shows Phase 7 (Map Interop) is complete and Phase 8 (Build Cutover) is next, but there is no detailed Phase 8 section in the migration plan describing the exact Docker/Compose cutover steps.

### Medium Severity

3. **Gradle task verification needed** - The migration status and plan reference `gradle -p kotlin/terrain-web-ui jsBrowserDistribution` and `gradle -p kotlin/terrain-web-ui syncFrontendDist` as locked tasks. These should be verified to work correctly before Phase 8 proceeds.

4. **web/frontend/dist state** - Current `web/frontend/dist/` contains the legacy React/Vite build (hashed Vite assets like `index-BUtq5_SC.js`), not the Kotlin web UI build. This is expected since Phase 8 hasn't been executed, but the cutover process needs clear documentation about:
   - When and how to clear the old build
   - How to verify the Kotlin assets are correctly synced
   - How to validate Ktor serves the new assets

5. **Missing explicit Node.js deprecation notice** - The plan states "no supported runtime path may depend on React, TypeScript, or Vite after cutover" but doesn't explicitly document when Node.js becomes unsupported in the development workflow.

### Low Severity

6. **Cross-platform command consistency** - Some Gradle commands in the docs use Unix-style paths (`./kotlin/...`) which may confuse Windows users. The docs should consistently show platform-appropriate examples.

7. **Status file could indicate pending browser smoke test more prominently** - The migration status mentions "pending: browser smoke test for Phase 7 map-backed preview work" but this is buried in the verification section. A more prominent "Blockers" or "Pending Verification" section would improve visibility.

## Verification Checklist Status

Based on file inspection:

| Item | Status | Notes |
|------|--------|-------|
| `kotlin/terrain-web-ui/` module exists | ✅ | Source files present |
| `build.gradle.kts` configured | ✅ | Kotlin/JS IR + Compose HTML |
| Production build task defined | ✅ | `jsBrowserDistribution` |
| Sync task defined | ✅ | `syncFrontendDist` |
| Production assets generated | ✅ | `build/dist/js/productionExecutable/` contains terrain-web-ui.js |
| `web/frontend/dist/` synced | ❌ | Still contains old React build |
| Source files complete | ✅ | Api.kt, AppState.kt, Main.kt, MapLibre.kt, Models.kt |

## Documentation Alignment

| Document | Purpose | Quality |
|----------|---------|---------|
| `kotlin-web-ui-migration-plan.md` | Canonical plan | Good - comprehensive phases |
| `kotlin-web-ui-migration-status.md` | Session handoff | Good - clear current state |
| `kotlin-web-ui-session-runbook.md` | Session guidance | Good - clear rules |
| `kotlin-web-ui-tech-spike.md` | Phase 1 decisions | Good - records decisions |
| `README.md` | Project overview | Good - doesn't mention web UI migration yet |
| `web/README.md` | Web stack docs | Good - still documents React frontend |

## Ambiguities for Next Session

1. **When should README files be updated?** - The root `README.md` and `web/README.md` still document the React/Vite frontend. They should be updated during Phase 8 or Phase 10, but this isn't explicitly scheduled.

2. **Legacy frontend cleanup scope** - Phase 10 says "remove legacy React/Vite frontend" but doesn't specify if this includes:
   - Removing `web/frontend/src/` entirely
   - Removing `web/frontend/package.json`
   - Updating `.gitignore`
   - Archiving vs deleting

3. **Verification criteria for Phase 8** - The plan mentions "Compose stack runs Kotlin backend plus Kotlin UI assets" but doesn't define specific verification steps for:
   - How to confirm Ktor is serving the right assets
   - How to test the production Docker build locally
   - What constitutes a successful smoke test

## Verdict

**Ready with minor clarifications needed**

The documentation is comprehensive and sufficient for the next session to proceed with Phase 8 (Build Cutover). However, the following should be addressed before or during Phase 8:

1. Add explicit Phase 8 section to `kotlin-web-ui-migration-plan.md` with Docker/Compose cutover steps
2. Clarify when and how to update README files
3. Define explicit verification steps for Phase 8 completion

## Recommendations for Phase 8

1. Update `web/frontend/dist/` by running:
   ```bash
   gradle -p kotlin/terrain-web-ui syncFrontendDist
   ```

2. Verify the sync worked by checking that `web/frontend/dist/` contains:
   - `terrain-web-ui.js`
   - `index.html` (Kotlin-generated)
   - `app.css`
   - `maplibre-gl.css`

3. Test Ktor serving by:
   - Running `gradle :terrain-web:run`
   - Accessing `http://127.0.0.1:8080/`
   - Verifying the Kotlin UI loads (not React)

4. Update documentation before Phase 9:
   - `docs/kotlin-web-ui-migration-status.md` with Phase 8 completion
   - `README.md` and `web/README.md` with Kotlin UI build instructions
   - Remove or deprecate React/Vite references

## Cross-References

- Migration plan: `docs/kotlin-web-ui-migration-plan.md`
- Current status: `docs/kotlin-web-ui-migration-status.md`
- Session runbook: `docs/kotlin-web-ui-session-runbook.md`
- Tech spike: `docs/kotlin-web-ui-tech-spike.md`
- Plan review: `docs/reviews/kotlin-web-ui-migration-plan-review.md`

## Follow-Up Applied

- Phase 10 removed legacy React/Vite source and config files from `web/frontend/`.
- `README.md`, `web/README.md`, `AGENTS.md`, and `start-web.cmd` now document and use the Kotlin/JS Compose UI workflow.
- `web/Dockerfile` now builds frontend assets from `kotlin/terrain-web-ui/` with Gradle.
