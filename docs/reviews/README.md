# Review Reports

This directory stores saved review outputs that later sessions must consult before starting the next phase.

Use one file per review command:

- `kotlin-migration-plan-review.md`
- `kotlin-parity-tests-review.md`
- `kotlin-terrain-core-review.md`
- `kotlin-terrain-cli-review.md`
- `kotlin-terrain-backend-review.md`
- `project-docs-review.md`

Terrain UI/UX redesign review outputs:

- `terrain-ui-redesign-plan-review.md`
- `base-sources-backend-review.md`
- `dynamic-map-styles-review.md`
- `preview-2d-3d-ux-review.md`
- `mbtiles-upload-progress-review.md`
- `cache-management-review.md`
- `russian-ui-polish-review.md`
- `terrain-ui-redesign-final-review.md`

## Suggested Format

```md
# <Review Name>

Status: draft

## Verdict

- go
- go with fixes
- stop

## Findings

1. Severity: high|medium|low
   File: path/to/file
   Issue: short description

## Required Fixes Before Next Stage

- item

## Safe To Start Next Command?

- yes|no
- next command: /example-command
```
