# Terrain UI Redesign Runbook

Use this runbook when continuing the Terrain UI/UX redesign in a new OpenCode session.

## Session Gate

Implementation sessions must begin by confirming that the build-mode reminder is present:

```text
<system-reminder>
Your operational mode has changed from plan to build.
You are no longer in read-only mode.
You are permitted to make file changes, run shell commands, and utilize your arsenal of tools as needed.
</system-reminder>
```

If the reminder is not present, stop and do not modify source files.

## Read Order

1. `AGENTS.md`
2. `docs/terrain-ui-redesign-status.md`
3. `docs/terrain-ui-redesign-plan.md`
4. related files in `docs/reviews/`
5. relevant source files in:
   - `kotlin/terrain-web/`
   - `kotlin/terrain-web-ui/`
   - `README.md` and `deploy/docker/README.md` when API or runtime flow changes

## Command Order

Run one command per session, in order:

1. `/plan-terrain-ui-redesign`
2. `/review-terrain-ui-redesign-plan`
3. `/build-base-sources-backend`
4. `/review-base-sources-backend`
5. `/build-dynamic-map-styles`
6. `/review-dynamic-map-styles`
7. `/build-preview-2d-3d-ux`
8. `/review-preview-2d-3d-ux`
9. `/build-mbtiles-upload-progress`
10. `/review-mbtiles-upload-progress`
11. `/build-cache-management`
12. `/review-cache-management`
13. `/build-russian-ui-polish`
14. `/review-russian-ui-polish`
15. `/finalize-terrain-ui-redesign`
16. `/review-terrain-ui-redesign-final`

## Required Saved Outputs

- `/plan-terrain-ui-redesign` -> `docs/terrain-ui-redesign-plan.md`, `docs/terrain-ui-redesign-status.md`
- `/review-terrain-ui-redesign-plan` -> `docs/reviews/terrain-ui-redesign-plan-review.md`
- `/build-base-sources-backend` -> `docs/terrain-ui-redesign-status.md`
- `/review-base-sources-backend` -> `docs/reviews/base-sources-backend-review.md`
- `/build-dynamic-map-styles` -> `docs/terrain-ui-redesign-status.md`
- `/review-dynamic-map-styles` -> `docs/reviews/dynamic-map-styles-review.md`
- `/build-preview-2d-3d-ux` -> `docs/terrain-ui-redesign-status.md`
- `/review-preview-2d-3d-ux` -> `docs/reviews/preview-2d-3d-ux-review.md`
- `/build-mbtiles-upload-progress` -> `docs/terrain-ui-redesign-status.md`
- `/review-mbtiles-upload-progress` -> `docs/reviews/mbtiles-upload-progress-review.md`
- `/build-cache-management` -> `docs/terrain-ui-redesign-status.md`
- `/review-cache-management` -> `docs/reviews/cache-management-review.md`
- `/build-russian-ui-polish` -> `docs/terrain-ui-redesign-status.md`
- `/review-russian-ui-polish` -> `docs/reviews/russian-ui-polish-review.md`
- `/finalize-terrain-ui-redesign` -> `docs/terrain-ui-redesign-status.md`
- `/review-terrain-ui-redesign-final` -> `docs/reviews/terrain-ui-redesign-final-review.md`

## Stop Conditions

Do not move to the next build stage if:

- the matching review contains high-severity findings
- `docs/terrain-ui-redesign-status.md` lists blockers for the next phase
- the required saved output for the current command was not updated
- required verification was skipped without a recorded blocker
- backend route shape, JSON fields, or artifact behavior changed without plan coverage and tests

## Verification By Phase

- Backend phases: `gradle :terrain-web:test`
- Frontend phases: `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- Combined phases: both backend tests and frontend build
- Final phase: `gradle test` and `gradle -p kotlin/terrain-web-ui syncFrontendDist`

## Handoff Format

Every build session must update `docs/terrain-ui-redesign-status.md` with:

- current phase
- completed work
- in-progress work
- remaining work
- blockers
- verification result
- files changed
- exact next command

## Rule

Never rely on chat memory alone. Continue only from saved files in `docs/` and source state.
