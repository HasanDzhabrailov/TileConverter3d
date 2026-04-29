# Parity Fixtures

This directory is the canonical fixture root for Kotlin migration parity verification.

## Rules

- Store only locked baseline inputs and expected outputs here.
- Generate goldens from the established baseline behavior being preserved.
- Keep committed text and JSON fixtures UTF-8 with `\n` line endings.
- Avoid host-specific absolute paths, timestamps, and platform-specific formatting in saved fixtures.
- Do not replace a golden fixture without updating `docs/kotlin-parity-matrix.md` or the matching review and status files when the contract changes intentionally.

## Layout

- `inputs/`: raw HGT, upload, bbox, and MBTiles fixture inputs
- `goldens/`: expected outputs, normalized SQL snapshots, and decoded PNG or RGBA snapshots when exact PNG bytes are not stable
- `contracts/`: saved CLI, HTTP, WebSocket, TileJSON, style, and validation snapshots
- `reports/`: saved parity diff reports from local or CI verification runs

## Required Manifest

Track every fixture in `manifest.json` with:

- `id`
- `area`
- `baseline_source`
- `inputs`
- `outputs`
- `comparison`
- `severity`
- `normalization`
- `notes`

Later sessions must extend the existing fixtures here before claiming parity closure.
