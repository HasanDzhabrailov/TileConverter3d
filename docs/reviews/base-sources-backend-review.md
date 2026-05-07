# Base Sources Backend Review

Status: final

## Verdict

- passed

## Findings

- No findings.

## Resolved Findings

- `POST` and `PUT /api/base-sources` now wrap `call.receive()` in route-level error handling and return `ErrorPayload` for malformed or incomplete JSON requests.
- Builtin seeding no longer changes `updated_at` on every startup; timestamps remain stable unless builtin source content changes.
- Validation tests now cover blank names, missing `{z}`, `{x}`, `{y}` tokens, invalid schemes, and `max_zoom` boundaries.
- Lifecycle and not-found tests now cover repeated builtin seed initialization and unknown `PUT`/`DELETE` responses.

## Notes

- SQLite storage uses `Path.resolve`, creates the database parent directory, and connects through `databasePath.toAbsolutePath()`, which is cross-platform for the supported Windows/Linux/macOS targets.
- Builtin source IDs are stable and seed logic is idempotent for the current single-backend process model.
- Builtin edit/delete protection returns `409` and custom validation returns `422`; malformed request bodies return `400` with the API error shape.
- `gradle :terrain-web:test` passed after the fixes.

## Residual Risks

- The repository synchronizes operations per repository instance. That is sufficient for the current application lifecycle, but multiple backend processes sharing one SQLite file would still rely on SQLite constraints rather than app-level coordination.

## Recommended Next Command

`/build-dynamic-style-base-sources`
