# Kotlin Parity Matrix

Status: active canonical parity harness

This file is the canonical saved parity-test artifact for the Kotlin migration. Later sessions must read this file before changing converter, CLI, backend, or project docs behavior.

## Goal

Lock down the established external behavior before more migration changes land.

Parity means Kotlin output and runtime behavior must match the accepted migration baseline across:

- HGT filename parsing
- supported grid size validation
- HGT orientation and edge sampling
- bilinear interpolation
- void-value handling
- bounds and tile coverage math
- Terrain-RGB encoding and decoding
- RGBA and PNG tile generation
- MBTiles writing and XYZ-to-TMS row mapping
- TileJSON generation
- `style.json` generation
- backend route payloads
- WebSocket job event structure
- uploaded MBTiles metadata, style, TileJSON, and tile serving
- request validation parity against the established API contract

## Canonical Harness Artifacts

- `docs/kotlin-parity-matrix.md`: saved contract and verification rules
- `kotlin/parity-fixtures/README.md`: fixture layout and regeneration rules
- `kotlin/parity-fixtures/manifest.json`: fixture inventory and comparison policy template
- `kotlin/parity-fixtures/inputs/`: locked raw fixture inputs
- `kotlin/parity-fixtures/goldens/`: locked expected outputs from the established baseline
- `kotlin/parity-fixtures/contracts/`: HTTP, WebSocket, CLI, and metadata contract fixtures
- `kotlin/parity-fixtures/reports/`: saved mismatch reports when parity work is run locally or in CI

## Fixture Strategy

Golden fixtures must be generated from the established behavior being preserved. Until that baseline capture is complete, no later session may claim parity is closed.

Required fixture groups:

### Core HGT Fixtures

- valid `1201x1201` input
- valid `3601x3601` input
- malformed or truncated input
- unsupported grid size input
- mixed-resolution directory input
- edge-location filenames for north, south, east, and west parsing
- fixtures with negative elevations, high positive elevations, and void samples (`-32768`)

### Sampling Fixtures

- exact-corner probes
- exact-edge probes
- center probes
- sub-cell probes that exercise bilinear interpolation
- seam probes that cross neighboring HGT tiles
- outside-coverage probes that must remain transparent or absent

### Coverage Fixtures

- single-tile bounds
- multi-tile bounds
- manual bbox input
- zoom ranges that expose min or max edge rounding behavior
- cases that prove exact tile count and `(z/x/y)` coverage

### Output Fixtures

- PNG terrain tiles
- filesystem XYZ tile pyramids
- MBTiles outputs
- `tiles.json`
- `style.json`
- uploaded `.mbtiles` examples for raster and raster-dem detection

### Backend Contract Fixtures

- route request and response snapshots
- request validation error payloads
- job lifecycle payloads
- WebSocket event transcripts for pending, running, completed, and failed jobs
- uploaded MBTiles metadata, style, mobile style, TileJSON, and tile responses

## Parity Test Structure

Tests must be organized by external contract surface rather than by implementation detail.

### CLI Parity

Verify:

- flag names
- defaults
- required arguments
- validation failures
- exit behavior
- generated artifact names
- output directory layout

### Terrain Core Parity

Verify:

- HGT filename parsing
- supported size validation
- orientation and north or south row handling
- bilinear interpolation rules
- void handling rules
- bounds union and tile coverage math
- Terrain-RGB encoding and decoding
- tile RGBA bytes
- PNG generation semantics
- MBTiles metadata and row mapping
- TileJSON and style defaults

### Backend Parity

Verify:

- route shapes
- status codes
- JSON field names
- request validation responses
- upload to job to artifact workflow
- tile serving behavior
- job `tiles.json` and `style.json` relative URL rules
- WebSocket event ordering and field names
- uploaded MBTiles metadata, style, TileJSON, and tile serving

## Comparison Rules

Use the strictest comparison that still isolates real contract drift from non-contractual formatting.

### Exact Byte Match Required

- locked PNG tile files when encoder output is stable
- tile payload bytes served from uploaded MBTiles
- MBTiles tile blobs when the stored payload is contract-sensitive
- HTTP status codes and content types
- WebSocket event names and required field values

### Semantic Match Allowed With Exact Decoded Content

- PNG files if ancillary chunk ordering or compression differs, but decoded RGBA bytes must still match exactly
- SQLite container bytes may differ, but queried metadata rows and `tiles` rows must match exactly after normalization
- JSON formatting and key order may differ, but required keys, values, URL forms, and field presence must match exactly

### Numeric Rules

- parsed elevations and interpolation results must match exactly at the contract boundary unless a saved fixture explicitly records a tolerated floating-point delta
- bounds and zoom-derived coverage values must match the locked fixture formatting rule for that contract surface
- Terrain-RGB encode and decode probes must remain exact at the byte and decoded-elevation boundary

### Normalization Rules

- normalize line endings to `\n` for committed text and JSON fixtures
- normalize unordered JSON object comparison by parsed semantic equality, not source key order
- sort SQL row comparisons by stable keys before diffing
- normalize path separators in the harness only, never in runtime contract output
- avoid timestamps, host-specific temp paths, and locale-dependent formatting in generated fixtures

## Failure Severity Rules

### Release Blockers

- HGT orientation drift
- bilinear interpolation mismatch
- void handling mismatch
- bounds or tile coverage off-by-one differences
- Terrain-RGB pixel mismatch
- MBTiles XYZ-to-TMS row mapping errors
- backend status-code or payload-schema drift on established routes
- WebSocket schema or event-order drift
- uploaded MBTiles behavior drift
- any remaining supported Python runtime dependency in runtime flow, Docker, Compose, scripts, or user-facing docs

### High Severity

- CLI flag or default mismatch
- TileJSON or style contract field drift
- MBTiles metadata drift
- artifact URL shape changes
- platform-specific path or filename drift
- request validation behavior that changes accepted or rejected inputs

### Medium Severity

- non-contractual JSON formatting differences
- PNG ancillary metadata differences with identical decoded pixels
- wording-only error message changes where the status code and payload contract remain intact

### Low Severity

- documentation wording cleanup
- cosmetic log-line differences that are not part of a saved route or WebSocket contract

## Platform Consistency Checks

Every parity suite must be suitable for Windows, Linux, and macOS.

Required platform checks:

- stable UTF-8 text and JSON fixture generation
- line-ending stability
- path separator independence
- file-locking behavior for MBTiles and SQLite
- no shell-specific runtime assumptions
- no Python subprocess dependency
- timezone-independent generated metadata
- stable local host and public URL resolution rules where the contract fixes them
- identical decoded PNG pixels and normalized MBTiles metadata across platforms

## Open Blockers Later Sessions Must Respect

- A real parity corpus now exists for core contracts, CLI defaults and layout, backend HTTP snapshots, successful and failure WebSocket transcripts, and uploaded raster plus raster-dem plus malformed and missing-metadata MBTiles snapshots.
- Uploaded MBTiles parity is still built from deterministic Kotlin SQLite builders rather than committed binary `.mbtiles` fixtures.
- No saved cross-platform parity run has been recorded yet for Windows, Linux, and macOS.
- The new fixture-driven tests were added, but this session could not execute Gradle because `gradle` was not available in the local environment path.
- No saved fixture regeneration command exists yet because baseline-capture commands still need to be finalized without reintroducing Python runtime expectations.

## Required Harness Layout Rules

Each fixture entry in `kotlin/parity-fixtures/manifest.json` must include:

- stable fixture id
- coverage area
- baseline source
- raw inputs
- expected outputs
- comparison mode
- normalization rules
- severity if the fixture fails
- notes about known contract edges

All committed text or JSON fixtures must use UTF-8 and `\n` line endings.

When a parity defect is found, the fix is not complete until a locked fixture or contract snapshot is added or updated with explicit approval.

## Exit Criteria For The Parity Stage

The parity-test stage is only complete when all of the following are true:

- the golden fixture corpus is captured from the established baseline
- fixture manifests exist for core, CLI, backend, and uploaded MBTiles contract coverage
- byte-level and semantic comparison rules are saved and followed
- blocker-class mismatches are resolved or explicitly called out as cutover blockers
- Windows, Linux, and macOS parity checks are all recorded
