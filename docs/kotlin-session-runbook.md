# Kotlin Session Runbook

Use this file to run the migration across separate OpenCode sessions.

## Command Order

Run one command per session, in this order:

1. `/plan-kotlin-migration`
2. `/review-kotlin-migration-plan`
3. `/add-kotlin-parity-tests`
4. `/review-kotlin-parity-tests`
5. `/build-kotlin-terrain-core`
6. `/review-kotlin-terrain-core`
7. `/build-kotlin-terrain-cli`
8. `/review-kotlin-terrain-cli`
9. `/build-kotlin-terrain-backend`
10. `/review-kotlin-terrain-backend`
11. `/update-project-docs`
12. `/review-project-docs`
13. `/run-kotlin-cutover`

## Before Starting Session 1

- Open the repo root in OpenCode.
- Read `AGENTS.md`.
- Read `docs/kotlin-migration-plan.md`, `docs/kotlin-parity-matrix.md` if it exists, `docs/kotlin-migration-status.md`, and `docs/reviews/` if they already contain content.

Canonical files to consult across sessions:

- `AGENTS.md`
- `docs/kotlin-session-runbook.md`
- `docs/kotlin-migration-plan.md`
- `docs/kotlin-parity-matrix.md`
- `docs/kotlin-migration-status.md`
- `docs/kotlin-cutover-report.md`
- `docs/reviews/README.md`
- `docs/reviews/kotlin-migration-plan-review.md`
- `docs/reviews/kotlin-parity-tests-review.md`
- `docs/reviews/kotlin-terrain-core-review.md`
- `docs/reviews/kotlin-terrain-cli-review.md`
- `docs/reviews/kotlin-terrain-backend-review.md`
- `docs/reviews/project-docs-review.md`

## Session 1

Run:

```text
/plan-kotlin-migration
```

Expected saved output:

- `docs/kotlin-migration-plan.md`

Do not continue until that file is updated with a concrete plan.

## When To Start The Next Session

Start the next session only when the current command has finished and the required saved file has been updated.

Required saved outputs by stage:

- `/plan-kotlin-migration` -> `docs/kotlin-migration-plan.md`
- `/review-kotlin-migration-plan` -> `docs/reviews/kotlin-migration-plan-review.md`
- `/add-kotlin-parity-tests` -> `docs/kotlin-parity-matrix.md` and `docs/kotlin-migration-status.md`
- `/review-kotlin-parity-tests` -> `docs/reviews/kotlin-parity-tests-review.md`
- `/build-kotlin-terrain-core` -> `docs/kotlin-migration-status.md`
- `/review-kotlin-terrain-core` -> `docs/reviews/kotlin-terrain-core-review.md`
- `/build-kotlin-terrain-cli` -> `docs/kotlin-migration-status.md`
- `/review-kotlin-terrain-cli` -> `docs/reviews/kotlin-terrain-cli-review.md`
- `/build-kotlin-terrain-backend` -> `docs/kotlin-migration-status.md`
- `/review-kotlin-terrain-backend` -> `docs/reviews/kotlin-terrain-backend-review.md`
- `/update-project-docs` -> `docs/kotlin-migration-status.md`
- `/review-project-docs` -> `docs/reviews/project-docs-review.md`
- `/run-kotlin-cutover` -> `docs/kotlin-cutover-report.md` and `docs/kotlin-migration-status.md`

## Stop Conditions

Do not move to the next build stage if:

- the matching review file contains high-severity findings
- the status file says blockers remain for the next stage
- the canonical parity file `docs/kotlin-parity-matrix.md` is required for the current stage but missing or stale
- Python runtime dependency is still present in runtime, Docker, Compose, scripts, or docs
- the saved outputs for the current stage were not written

## Quick Rule

- One command per session.
- Review after every build step.
- Move forward only from saved files in `docs/`, never from chat memory alone.
