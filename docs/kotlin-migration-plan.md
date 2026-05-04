# Kotlin Migration Plan

Status: active canonical plan

This file is the canonical saved migration plan for completing the Kotlin/KMP cutover of the backend stack. Later sessions should treat this as the source of truth for sequencing, parity priorities, and cutover criteria.

## Goal

Make the full supported backend stack Kotlin-only at runtime while preserving existing behavior:

- migrate `converter/terrain_converter/*`
- migrate `web/backend/app/*`
- keep Compose-managed web stack behavior compatible
- target Ktor for the backend
- preserve cross-platform support for Windows, Linux, and macOS
- remove all supported Python runtime, scripts, Docker, and Compose dependencies

Behavior to preserve:

- CLI flags, defaults, and output layout
- HTTP route shapes and JSON fields
- WebSocket path and event shape
- upload -> conversion job -> logs/status -> artifacts -> tile serving workflow
- Terrain-RGB encoding and PNG semantics
- MBTiles metadata and XYZ/TMS row semantics
- generated artifact names and filesystem layout
- uploaded `.mbtiles` handling, including `source_type` auto-detection and `/style-mobile`

## Current State

The repo is already mostly runtime-migrated:

- `kotlin/terrain-core/` contains the converter core.
- `kotlin/terrain-cli/` contains a Kotlin CLI.
- `kotlin/terrain-web/` contains a Ktor backend that already runs conversion directly through Kotlin code.
- `web/docker-compose.yml` and `web/Dockerfile` already target the Kotlin backend.

Remaining migration work is mostly parity, cleanup, and canonical cutover work:

- `docs/kotlin-migration-plan.md` was a placeholder and is now the saved plan.
- legacy Python sources still exist and remain the main reference for some contracts.
- parity gaps are still likely around HTTP validation, log/WebSocket behavior, host/public URL resolution, and exact CLI behavior.
- the repo still needs an explicit path to remove any remaining supported Python runtime expectations.

## Target Module Structure

### Required KMP End State

The cutover target is not only Kotlin-only runtime. It is a Kotlin/KMP architecture with a shared terrain core plus JVM-specific delivery layers.

Required end state:

- `kotlin/terrain-core/` becomes the shared KMP module for platform-neutral terrain logic
- `kotlin/terrain-cli/` remains Kotlin/JVM and depends on the shared core
- `kotlin/terrain-web/` remains Kotlin/JVM with Ktor and depends on the shared core
- platform-neutral logic moves into KMP shared source sets
- JVM-only filesystem, SQLite/MBTiles driver, image encoding, and Ktor integration stay in JVM-specific source sets or JVM-only modules

KMP-complete means all of the following are true:

- HGT parsing, bounds math, sampling, Terrain-RGB encoding, tile coverage math, TileJSON generation, and style generation live in shared KMP code
- CLI argument parsing, backend routing, multipart parsing, filesystem storage, WebSocket transport, and container/runtime wiring remain JVM-specific
- any PNG or MBTiles implementation that cannot be fully commonized is isolated behind shared interfaces with JVM implementations
- the repo no longer describes the cutover as complete if only a Kotlin/JVM rewrite exists without the shared KMP core split

### Legacy To Kotlin Mapping

| Legacy module | Kotlin target | Responsibility |
|---|---|---|
| `converter/terrain_converter/cli.py` | `kotlin/terrain-cli/src/main/kotlin/com/terrainconverter/cli/TerrainConverterCli.kt` | canonical CLI contract, flag parsing, defaults, exit behavior |
| `converter/terrain_converter/hgt.py` | `kotlin/terrain-core/src/main/kotlin/com/terrainconverter/core/Hgt.kt` | HGT parsing, supported input validation |
| `converter/terrain_converter/bbox.py` | `kotlin/terrain-core/src/main/kotlin/com/terrainconverter/core/Bounds.kt` | bounds math, union, clamp, coverage inputs |
| `converter/terrain_converter/tiling.py` | `kotlin/terrain-core/src/main/kotlin/com/terrainconverter/core/Tiling.kt`, `GridSampling.kt`, `TerrainRgb.kt`, `PngWriter.kt` | tile coverage, sampling, Terrain-RGB encoding, PNG output |
| `converter/terrain_converter/mbtiles.py` | `kotlin/terrain-core/src/main/kotlin/com/terrainconverter/core/Mbtiles.kt` | MBTiles metadata and tile writes |
| `converter/terrain_converter/tilejson.py` | `kotlin/terrain-core/src/main/kotlin/com/terrainconverter/core/TileJson.kt` | TileJSON generation |
| `converter/terrain_converter/style_json.py` | `kotlin/terrain-core/src/main/kotlin/com/terrainconverter/core/StyleJson.kt` | style generation |
| `converter/terrain_converter/validate.py` | `kotlin/terrain-core/src/main/kotlin/com/terrainconverter/core/Conversion.kt` and validation helpers | zoom/input validation, mixed-resolution rejection |
| `web/backend/app/main.py` | `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/TerrainWebServer.kt` | Ktor routes, upload flow, server-info, artifacts, tile serving |
| `web/backend/app/jobs.py` | `terrain-web` job lifecycle code | in-memory job state, status, result, artifact URLs |
| `web/backend/app/websocket_manager.py` | `terrain-web` websocket manager | WS connection lifecycle and broadcasts |
| `web/backend/app/converter_runner.py` | `terrain-web` conversion runner + `terrain-core` orchestration | zip extraction, HGT discovery, conversion invocation, post-processing |
| `web/backend/app/tilejson.py` | `terrain-web` job document builder using `terrain-core` JSON builders | relative job `tiles.json` and `style.json` documents |
| `web/backend/app/mbtiles_server.py` | `terrain-web` MBTiles serving code | uploaded MBTiles metadata, tiles, style, mobile style |

### Target Kotlin Responsibilities

`kotlin/terrain-core/`

- shared KMP converter logic only
- HGT parsing
- bounds and tile math
- Terrain-RGB encoding
- TileJSON and style generation helpers
- converter orchestration and parity tests

`kotlin/terrain-core/` JVM-specific implementation area

- PNG generation if image encoding cannot be fully shared
- MBTiles writing if the selected SQLite path is JVM-only
- filesystem-bound adapters used by CLI and backend

`kotlin/terrain-cli/`

- CLI parsing and usage text
- exit codes and stdout/stderr behavior
- output path/default contract
- CLI parity tests

`kotlin/terrain-web/`

- Ktor routes and request validation
- multipart upload parsing
- job lifecycle and logging
- WebSocket events
- storage layout and artifact serving
- uploaded MBTiles runtime
- host/public URL resolution
- backend parity tests

### Recommended Internal `terrain-web` Split

Keep one Gradle module, but split the large backend file into focused Kotlin files as the migration stabilizes:

- `TerrainWebServer.kt`: route wiring only
- `JobManager.kt`: job state and lifecycle
- `WebSocketManager.kt`: WS registry and broadcast logic
- `ConversionRunner.kt`: zip extraction, HGT preparation, converter execution
- `JobDocuments.kt`: relative job `tiles.json` and `style.json`
- `PublicUrlResolver.kt`: `TERRAIN_WEB_PUBLIC_HOST` and host selection logic
- `MultipartParsing.kt`: multipart/form parsing and validation
- `MbtilesService.kt` or `MbtilesEndpoints.kt`: uploaded MBTiles handling
- keep `Models.kt` and `Storage.kt`

This is a maintainability refactor only. Do not redesign the external contract.

## Migration Phases

## Phase 0: Baseline And Contract Lock

Objective: define and save the behavior being preserved before making more code changes.

Tasks:

- treat the legacy Python converter and backend as the behavioral reference for parity checks
- save the canonical parity matrix and fixture inventory in `docs/kotlin-parity-matrix.md`
- build a parity matrix covering:
  - CLI flags, defaults, errors, exit behavior
  - output files and layout
  - HTTP routes and status codes
  - JSON field names and response shapes
  - WebSocket event shapes and ordering
  - job lifecycle transitions
  - public URL resolution behavior
  - uploaded MBTiles behavior
- capture representative fixtures for:
  - single HGT input
  - directory input
  - zip upload
  - manual bbox
  - invalid bbox
  - mixed `1201` and `3601` inputs
  - `scheme=tms`
  - uploaded raster MBTiles
  - uploaded raster-dem MBTiles
- keep `docs/kotlin-parity-matrix.md` updated as the saved contract artifact later sessions must read before implementation work

Dependencies:

- none

Verification before exit:

- this plan exists and is current
- `docs/kotlin-parity-matrix.md` is the named saved parity artifact for later sessions
- contract surfaces are listed explicitly
- later implementation phases reference a saved parity matrix or test inventory

## Phase 1: KMP Architecture Split

Objective: define and stage the actual Kotlin/KMP architecture before closing behavior gaps.

Tasks:

- convert `kotlin/terrain-core/` from a JVM-only implementation target into the shared KMP core for platform-neutral terrain logic
- define the exact `commonMain`, `commonTest`, `jvmMain`, and `jvmTest` responsibilities
- move platform-neutral logic into shared KMP code:
  - HGT parsing
  - bounds math
  - tile coverage math
  - grid sampling
  - Terrain-RGB encoding
  - TileJSON generation
  - style generation
- isolate JVM-only concerns behind clear adapters or JVM-specific source sets:
  - filesystem IO
  - PNG encoding if it remains JVM-only
  - MBTiles writing if it remains JVM-only
  - CLI process/runtime glue
  - Ktor/backend runtime glue
- document any intentionally JVM-only component that cannot move into shared KMP code during this migration

Dependencies:

- Phase 1 KMP architecture split complete

Verification before work:

- review this plan and `docs/kotlin-parity-matrix.md`
- verify the proposed KMP split still preserves the current CLI and backend contract

Verification after work:

- `kotlin/terrain-core/` has an explicit KMP source-set structure or an equivalent staged layout documented in the module
- all platform-neutral terrain behavior has a shared-code target
- any JVM-only remaining component is explicitly justified and isolated

## Phase 2: Terrain Core Parity Hardening

Objective: make `terrain-core` the single trusted converter implementation.

Tasks:

- audit Kotlin core against Python behavior for:
  - signed 16-bit big-endian HGT parsing
  - supported HGT sizes only
  - mixed-resolution rejection
  - bounds union and clamp behavior
  - XYZ coverage math
  - bilinear interpolation
  - void and transparent pixel behavior
  - Terrain-RGB encoding
  - PNG output semantics
  - MBTiles metadata and XYZ-to-TMS row flip
  - TileJSON and style defaults
- add fixture-driven regression tests for generated outputs
- verify worker-count invariance where concurrency is exposed

Dependencies:

- Phase 0 contract lock

Verification before work:

- review the parity matrix for core converter behavior
- confirm the shared KMP core split from Phase 1 is in place before parity hardening begins

Verification after work:

- run `gradle :terrain-core:test`
- compare generated fixture outputs for PNG, MBTiles metadata, `tiles.json`, and `style.json`
- leave no known core parity gap open before moving to backend parity

## Phase 3: CLI Parity Completion

Objective: make the Kotlin CLI the canonical CLI behavior.

Tasks:

- compare `TerrainConverterCli.kt` directly to `converter/terrain_converter/cli.py`
- preserve positional `inputs` contract
- preserve support for:
  - `--output-mbtiles`
  - `--tile-root`
  - `--tilejson`
  - `--style-json`
  - `--tiles-url`
  - `--minzoom`
  - `--maxzoom`
  - `--bbox`
  - `--tile-size`
  - `--scheme`
  - `--encoding`
  - `--name`
  - `--workers`
- close drift in defaults, validation, exit codes, help text, and summary output
- preserve output artifact names and directory layout

Dependencies:

- Phase 1 and Phase 2 complete

Verification before work:

- capture current CLI examples and legacy expected outputs

Verification after work:

- run `gradle :terrain-cli:test`
- run representative CLI conversions and verify output layout
- confirm docs examples still produce the expected artifacts

## Phase 4: Backend HTTP And Artifact Parity

Objective: close route, payload, validation, and artifact drift between FastAPI behavior and Ktor behavior.

Tasks:

- audit parity for these routes:
  - `GET /api/health`
  - `GET /api/server-info`
  - `POST /api/jobs`
  - `GET /api/jobs`
  - `GET /api/jobs/{jobId}`
  - `GET /api/jobs/{jobId}/logs`
  - `WS /ws/jobs/{jobId}`
  - `GET /api/jobs/{jobId}/downloads/{artifact}`
  - `GET /api/jobs/{jobId}/terrain/{z}/{x}/{y}.png`
  - `GET /api/jobs/{jobId}/base/{z}/{x}/{y}`
  - `GET /api/jobs/{jobId}/tilejson`
  - `GET /api/jobs/{jobId}/style`
  - `POST /api/mbtiles`
  - `GET /api/mbtiles`
  - `GET /api/mbtiles/{tilesetId}`
  - `GET /api/mbtiles/{tilesetId}/metadata`
  - `GET /api/mbtiles/{tilesetId}/tilejson`
  - `GET /api/mbtiles/{tilesetId}/style`
  - `GET /api/mbtiles/{tilesetId}/style-mobile`
  - `GET /api/mbtiles/{tilesetId}/{z}/{x}/{y}`
  - `GET /api/mbtiles/{tilesetId}/{z}/{x}/{y}.{ext}`

Explicit fixes required in this phase:

- fix HTTP validation drift:
  - multipart request contract for `POST /api/jobs`
  - `hgt_files` presence rules
  - optional `base_mbtiles` handling
  - multipart field presence rules
  - manual bbox field requirements
  - integer parsing for `minzoom`, `maxzoom`, `tile_size`
  - `bbox_mode` validation
  - `source_type` validation
  - tile coordinate parsing failures
  - `404` vs `422` vs `200` behavior
  - error JSON shape as `{ "detail": ... }`
- fix artifact document drift:
  - keep job `tiles.json` and `style.json` using relative `/api/jobs/...` URLs
  - keep public absolute URLs in job artifact fields only
  - keep base map separate from terrain DEM
  - preserve `scheme`, `encoding`, `tileSize`, hillshade, and preview style behavior

Dependencies:

- Phase 1 through Phase 3 complete

Verification before work:

- snapshot current Kotlin route behavior
- capture expected legacy responses for invalid and valid inputs
- capture request/response snapshots for `POST /api/jobs` success and validation failures

Verification after work:

- run `gradle :terrain-web:test`
- add response snapshots for happy-path and validation failures
- smoke test upload -> job -> logs -> downloads -> tilejson/style -> terrain tile in the existing frontend flow

## Phase 5: Log And WebSocket Parity

Objective: preserve live job progress behavior expected by the UI and any external clients.

Tasks:

- audit and align WebSocket behavior for:
  - initial message on connect
  - event shape for job updates
  - event shape for log lines
  - event ordering across pending, running, logs, completion, and failure
  - final state delivery on success and failure
  - disconnect cleanup
- close incomplete log parity by ensuring the backend emits a useful, stable log stream during Kotlin-native conversion
- if legacy behavior expects a command-like first log line, emit an equivalent synthetic Kotlin log line without reintroducing subprocess runtime coupling
- verify concurrent job behavior does not drop or reorder final state updates in a way that breaks the frontend

Explicit fix required in this phase:

- fix incomplete log/WebSocket parity

Dependencies:

- Phase 4 complete

Verification before work:

- record current WS payload ordering and job state timing

Verification after work:

- run `gradle :terrain-web:test`
- add dedicated WS/log integration tests
- verify the frontend job log and status stream against a real conversion run

## Phase 6: Host And Public URL Parity

Objective: align Kotlin public URL generation with the current backend contract.

Tasks:

- preserve `TERRAIN_WEB_PUBLIC_HOST` precedence
- preserve request-host fallback rules
- preserve localhost and private-network behavior
- preserve default-port suppression in absolute URLs
- align server-info output across:
  - local address
  - mobile/Wi-Fi address
  - current browser host when applicable
- ensure consistency for:
  - job artifact public URLs
  - uploaded MBTiles public URLs
  - generated TileJSON/style payloads
  - `GET /api/server-info`

Explicit fix required in this phase:

- fix host/public URL drift

Dependencies:

- Phase 4 complete

Verification before work:

- compare Python and Kotlin host resolution behavior for localhost and LAN scenarios

Verification after work:

- run `gradle :terrain-web:test`
- add tests for `TERRAIN_WEB_PUBLIC_HOST`, localhost, and non-default ports
- manually verify mobile/Wi-Fi URLs on a local network when possible

## Phase 7: Remove Remaining Python Runtime Dependency

Objective: make Python clearly unsupported for runtime, tooling, Docker, and Compose flows.

Tasks:

- remove or stop documenting any supported Python runtime path
- ensure no supported backend path shells out to Python or depends on Python packages
- ensure no supported Docker or Compose path references FastAPI, uvicorn, or Python converter packaging
- remove or archive Python packaging manifests from supported paths, including `pyproject.toml`, `requirements*.txt`, lockfiles, and Python-only startup metadata if they still imply supported runtime use
- remove or rewrite scripts and helper commands that still invoke Python or the legacy FastAPI backend for supported workflows
- remove Python runtime commands from CI or local verification instructions if they still exist
- mark any retained legacy Python source tree as reference-only in adjacent docs and ensure it is not part of supported run, build, or test commands
- search for remaining Python runtime assumptions in:
  - docs
  - scripts
  - package manifests
  - Docker files
  - Compose files
  - runbooks
  - CI commands if present
- keep legacy Python sources as reference-only until cutover is complete, then archive or remove them once parity tests fully replace them

Explicit fix required in this phase:

- remove any remaining Python runtime dependency

Dependencies:

- Phase 3 through Phase 5 complete

Verification before work:

- inventory all repo-supported run commands and deployment paths
- inventory any remaining Python manifests or scripts that could be mistaken for supported runtime entrypoints

Verification after work:

- supported runtime commands are Kotlin/JVM only
- Compose stack remains Kotlin-only
- grep/audit confirms no supported path depends on Python runtime
- no supported package manifest, script, CI job, or doc suggests Python remains a valid runtime path

## Phase 8: Internal Backend Cleanup And Cutover

Objective: reduce maintenance risk and complete canonical cutover without changing the contract.

Tasks:

- split `TerrainWebServer.kt` into focused files listed above
- keep changes internal to `terrain-web`
- avoid abstraction that changes current behavior
- update migration status, reviews, and user-facing docs
- archive or remove legacy Python implementation once parity confidence is sufficient

Dependencies:

- all behavior-critical parity phases complete

Verification before work:

- full behavior test suite is green before refactor-only changes begin

Verification after work:

- run `gradle :terrain-web:test`
- run `gradle -p kotlin/terrain-web-ui syncFrontendDist` if backend/UI flow code moved in a way that can affect integration
- confirm no external route or payload changed

## High-Risk Parity Areas

Prioritized risk list:

1. HTTP validation drift
2. incomplete log/WebSocket parity
3. host/public URL drift
4. KMP architecture drift that leaves shared logic trapped in JVM-only code
5. CLI behavior drift
6. job `tiles.json` and `style.json` drift
7. uploaded `.mbtiles` parity drift
8. cross-platform path, zip, and network behavior differences
9. lingering docs, manifests, CI, or scripts that still imply Python is supported runtime

Risk notes:

- FastAPI previously provided coercion and `422` semantics that Ktor must now reproduce deliberately.
- the frontend is sensitive to WebSocket event timing and artifact URL shape.
- public URL drift can break mobile preview and downloaded artifact links even when local browser flows still work.
- uploaded MBTiles behavior is part of the backend contract and must not be treated as optional.

## Parity Checklist

### Converter And CLI

- [ ] CLI flags match legacy names
- [ ] CLI defaults match legacy defaults
- [ ] positional inputs contract is preserved
- [ ] help and usage remain compatible
- [ ] invalid argument handling and exit codes are aligned
- [ ] platform-neutral terrain logic lives in shared KMP code
- [ ] JVM-only converter concerns are isolated behind explicit adapters or JVM source sets
- [ ] HGT parsing is signed 16-bit big-endian
- [ ] only `1201x1201` and `3601x3601` inputs are supported
- [ ] mixed resolutions are rejected unless the contract changes with tests and docs together
- [ ] bounds math is preserved
- [ ] tile coverage is preserved
- [ ] sampling and void handling are preserved
- [ ] Terrain-RGB encoding is preserved
- [ ] PNG transparency semantics are preserved
- [ ] filesystem output stays XYZ
- [ ] MBTiles rows are flipped to TMS in the writer
- [ ] output names and layout are preserved:
  - `terrain-rgb.mbtiles`
  - `terrain/{z}/{x}/{y}.png`
  - `terrain/tiles.json`
  - `style.json`

### Backend

- [ ] HTTP route shapes are preserved
- [ ] JSON field names are preserved
- [ ] status code behavior is preserved
- [ ] error payload shape remains `{ "detail": ... }`
- [ ] job lifecycle remains upload -> pending -> running -> completed/failed
- [ ] log retrieval behavior is preserved
- [ ] WebSocket path and message shapes are preserved
- [ ] public artifact URLs are preserved
- [ ] job `tiles.json` uses relative `/api/jobs/...` URLs
- [ ] job `style.json` uses relative `/api/jobs/...` URLs
- [ ] base MBTiles stays separate from terrain DEM
- [ ] backend flips `y` only for job terrain tile serving when `scheme=tms`
- [ ] uploaded `.mbtiles` APIs are preserved
- [ ] `source_type` auto-detection is preserved
- [ ] `/style-mobile` is preserved
- [ ] server-info payload remains compatible

### Verification Methods

- [ ] snapshot tests for JSON responses
- [ ] fixture comparisons for output files
- [ ] byte-level or metadata-level MBTiles comparisons where practical
- [ ] representative PNG checks where practical
- [ ] end-to-end frontend smoke checks

## Verification Strategy

Before each phase:

- read this plan
- read `docs/kotlin-migration-status.md`
- read the latest relevant review in `docs/reviews/`
- identify the exact contract surface being changed
- add or tighten tests before refactors where feasible

After each phase:

- core changes: run `gradle :terrain-core:test`
- CLI changes: run `gradle :terrain-cli:test`
- backend changes: run `gradle :terrain-web:test`
- frontend-impacting backend changes: run `gradle -p kotlin/terrain-web-ui syncFrontendDist`

End-of-stage verification when a phase is complete:

- run `gradle test`
- run `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- perform manual smoke checks for:
  - HGT upload
  - job start
  - live logs and status updates
  - output downloads
  - `GET /api/jobs/{jobId}/tilejson`
  - `GET /api/jobs/{jobId}/style`
  - terrain tile requests
  - uploaded MBTiles metadata, tilejson, style, style-mobile, and tile requests

## Cross-Platform Constraints

- supported runtime must remain Kotlin/JVM only on Windows, Linux, and macOS
- no runtime subprocess dependency on Python is allowed
- no OS-specific shell assumptions are allowed in runtime code or docs
- path handling must be safe across Windows and POSIX paths
- zip extraction must work on all supported platforms
- temp directory handling and cleanup must work on all supported platforms
- host/public URL behavior must be acceptable on all supported platforms
- launcher scripts and installed distributions must work on all supported platforms

## Cross-Platform Checklist

- [ ] Windows CLI run path verified
- [ ] Linux CLI run path verified
- [ ] macOS CLI run path verified
- [ ] Windows backend workflow verified
- [ ] Linux backend workflow verified
- [ ] macOS backend workflow verified
- [ ] no platform requires Python for supported workflows
- [ ] generated outputs are materially identical across platforms
- [ ] Compose stack remains the supported containerized flow

Acceptance criteria:

- CLI conversion succeeds on Windows, Linux, and macOS
- backend upload -> conversion -> preview -> download succeeds on Windows, Linux, and macOS
- no platform has a runtime dependency on Python

## Compose And Runtime Checklist

- [ ] `web/docker-compose.yml` remains Kotlin backend only
- [ ] `web/Dockerfile` remains Kotlin backend only
- [ ] frontend still proxies `/api` and `/ws` in development
- [ ] backend still serves built frontend assets when `web/frontend/dist` exists
- [ ] storage paths remain compatible under `web/data/...`
- [ ] environment variables remain documented and supported:
  - `TERRAIN_WEB_APP_NAME`
  - `TERRAIN_WEB_HOST`
  - `TERRAIN_WEB_PORT`
  - `TERRAIN_WEB_STORAGE_ROOT`
  - `TERRAIN_WEB_FRONTEND_DIST`
  - `TERRAIN_WEB_PUBLIC_HOST`
- [ ] no supported deployment path references Python runtime components

## Documentation Deliverables

Required documentation work during migration:

- [ ] maintain this file as the canonical migration plan
- [ ] update `docs/kotlin-migration-status.md` after every implementation stage
- [ ] update root `README.md` if runtime commands, packaging, or usage wording changes
- [ ] update `web/README.md` if backend workflow, Compose usage, or deployment wording changes
- [ ] update related docs and runbooks if runtime or usage changes
- [ ] keep docs concise and practical
- [ ] clearly state the target architecture is shared Kotlin/KMP core plus Kotlin/JVM CLI and Ktor backend
- [ ] clearly state Python is not required for supported runtime, scripts, Docker, or Compose flow
- [ ] if legacy Python sources remain temporarily, mark them reference-only until removal

## Recommended Migration Sequence

1. lock the contract and fixture inventory in `docs/kotlin-parity-matrix.md`
2. complete the KMP architecture split for shared terrain logic
3. finish terrain-core parity hardening
4. finish CLI parity hardening
5. close backend HTTP validation and artifact drift, including `POST /api/jobs`
6. close log/WebSocket parity gaps
7. close host/public URL drift
8. remove remaining supported Python runtime paths
9. refactor `terrain-web` internally for maintainability
10. run full verification and frontend smoke checks
11. update status/docs and complete cutover
12. archive or remove legacy Python code when parity confidence is sufficient

## Cutover Criteria

Do not declare the migration complete until all of the following are true:

1. Runtime independence
   - no supported runtime flow requires Python
   - no backend conversion path shells out to Python or depends on Python packages
   - no supported manifest, script, CI path, Docker path, or Compose path implies Python remains a valid runtime option

2. KMP architecture
   - `kotlin/terrain-core/` is the shared KMP core for platform-neutral terrain logic
   - any remaining JVM-only converter component is isolated and explicitly justified
   - the repo is not describing a JVM-only rewrite as KMP-complete

3. Parity
   - CLI behavior is verified against the saved contract
   - backend HTTP and WebSocket behavior is verified against the saved contract
   - `tiles.json`, `style.json`, MBTiles, and tile output semantics are verified
   - uploaded `.mbtiles` behavior is verified

4. Verification
   - `gradle test` passes
   - `gradle -p kotlin/terrain-web-ui syncFrontendDist` passes
   - parity fixtures and integration tests pass
   - manual end-to-end workflow passes

5. Cross-platform
   - Windows, Linux, and macOS verification is complete
   - no platform-specific runtime blocker remains

6. Documentation
   - this plan is current
   - `docs/kotlin-migration-status.md` is current
   - `docs/kotlin-parity-matrix.md` is current
   - `README.md` and `web/README.md` are aligned to the Kotlin-only runtime
   - outdated Python runtime instructions are removed or clearly marked unsupported

7. Operational clarity
   - Compose-managed web stack uses the Kotlin backend only
   - canonical ownership is clear:
     - converter behavior: `terrain-core`
     - CLI contract: `terrain-cli`
     - backend/API contract: `terrain-web`
