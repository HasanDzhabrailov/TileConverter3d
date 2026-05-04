# Kotlin Web UI Session Runbook

Use this runbook when continuing the Kotlin-only Web UI migration in a new session.

## Session Gate

Every implementation session must begin by confirming that the following reminder is present and applies to the current session:

```text
<system-reminder>
Your operational mode has changed from plan to build.
You are no longer in read-only mode.
You are permitted to make file changes, run shell commands, and utilize your arsenal of tools as needed.
</system-reminder>
```

If that reminder is not present, stop and do not continue implementation work in that session.

## Read Order

1. confirm the build-mode `system-reminder`
2. `docs/kotlin-web-ui-migration-status.md`
3. `docs/kotlin-web-ui-migration-plan.md`
4. related files in `docs/reviews/`
5. relevant source files in:
   - `kotlin/terrain-web-ui/`
   - `kotlin/terrain-web/`
   - `web/frontend/dist/` when checking generated served assets

## Rules

- treat `docs/kotlin-web-ui-migration-status.md` as the source of truth for current phase
- do not rely on prior chat context
- do not start implementation unless the build-mode `system-reminder` is present
- preserve backend contract unless the saved plan explicitly changes it
- update the status file after each completed implementation phase
- record blockers immediately
- do not skip verification for the current phase before starting the next one

## Phase Order

1. Tech Spike
2. Module Setup
3. Models and API
4. App State
5. Base UI Panels
6. Upload and Forms
7. Map Interop
8. Build Cutover
9. Verification
10. Cleanup

## Required Verification by Phase

- module/build changes: run the relevant Gradle build
- backend-impacting integration: run `gradle :terrain-web:test`
- UI cutover: verify built frontend assets are served by Ktor
- preview changes: smoke-test terrain and MBTiles preview

## Handoff Format

Each session must update:

- current phase
- completed work
- in-progress work
- remaining work
- blockers
- verification
- files changed
- exact next starting point
