# Repo Notes

- The project target is Kotlin/KMP only: shared KMP core, Kotlin CLI, Ktor backend, and a Compose-managed web stack.
- Preserve behavior over redesign: CLI flags, HTTP/WebSocket route shapes, JSON fields, artifact names, file layout, and Terrain-RGB semantics should remain compatible unless the task explicitly changes the contract.
- No Python runtime dependencies are allowed in the final system. Runtime, tooling, Docker, scripts, and docs should assume Kotlin/JVM and Compose.
- Keep the repo cross-platform for Windows, Linux, and macOS. Avoid OS-specific shell assumptions in runtime code and docs.

# High-Value Paths

- `kotlin/terrain-core/`: shared KMP/JVM terrain logic, HGT parsing, sampling, Terrain-RGB, PNG, MBTiles, TileJSON, style generation.
- `kotlin/terrain-cli/`: Kotlin CLI contract and conversion entrypoint.
- `kotlin/terrain-web/`: Ktor backend, jobs, WebSocket events, artifact routes, tile serving, and uploaded MBTiles support.
- `kotlin/terrain-web-ui/`: Kotlin/JS Compose UI for uploads, job status/logs, preview, downloads, and MBTiles browsing.
- `web/docker-compose.yml`: Compose entrypoint for the web stack.
- `docs/kotlin-migration-plan.md`: canonical saved migration plan that later sessions should follow once created.
- `docs/kotlin-migration-status.md`: canonical saved stage status that later sessions should update and follow.
- `docs/kotlin-session-runbook.md`: canonical command order for multi-session Kotlin/KMP migration work.
- `docs/reviews/`: saved review reports that later sessions should consult before starting the next phase.

# Commands

- Run all Kotlin tests: `gradle test`
- Run terrain core tests: `gradle :terrain-core:test`
- Run CLI tests: `gradle :terrain-cli:test`
- Run backend tests: `gradle :terrain-web:test`
- Run backend locally: `gradle :terrain-web:run`
- Run CLI locally: `gradle :terrain-cli:run --args="<hgt file or dir> --minzoom 8 --maxzoom 12"`
- Frontend dev: `gradle -p kotlin/terrain-web-ui jsBrowserDevelopmentRun`
- Frontend build: `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- Compose web stack: `docker compose -f web/docker-compose.yml up --build`

# Constraints

- Parity is more important than redesign.
- Keep terrain DEM separate from any base map MBTiles.
- Mixed `1201` and `3601` HGT inputs stay unsupported unless validation, compatibility expectations, and tests are updated together.
- Preserve MapLibre terrain defaults unless explicitly changing contract: `encoding=mapbox`, `tileSize=256`, `scheme=xyz`, default tiles URL `http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png`.
- Job `tiles.json` and `style.json` use relative `/api/jobs/...` URLs; public absolute URLs are exposed separately in job artifact fields.
- Converter output is written to the filesystem tile pyramid as XYZ. MBTiles rows are flipped to TMS in the writer, and the backend flips `y` only when serving job tiles for `scheme=tms`.
- Uploaded `.mbtiles` handling is part of the backend contract, including `source_type` auto-detection and `/style-mobile` for mobile clients.
- Preserve the backend workflow: upload -> conversion job -> logs/status -> artifacts -> tile serving.
- No subprocess dependency on Python is allowed. Conversion, MBTiles handling, validation, logging, and serving must run natively through Kotlin.
- If runtime flow changes, update `README.md`, `web/README.md`, and related docs with concise real commands.

# Verification

- Core changes: run `gradle :terrain-core:test`.
- CLI changes: run `gradle :terrain-cli:test`.
- Backend changes: run `gradle :terrain-web:test`.
- Frontend changes: run `gradle -p kotlin/terrain-web-ui syncFrontendDist`.
- Backend changes that affect UI flows should still be checked against the Kotlin web UI.
- Validate behavior on Windows, Linux, and macOS.
