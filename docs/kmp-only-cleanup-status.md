# KMP-Only Cleanup Status

**Started**: 2025-05-04
**Status**: In Progress - Phase 8 (Verification)

## Completed Tasks

### Phase 1: Documentation and Planning ✓
- [x] Create cleanup plan (docs/kmp-only-cleanup-plan.md)
- [x] Create this status file (docs/kmp-only-cleanup-status.md)

### Phase 2: File Removals ✓
- [x] Remove `archive/legacy-python/`
- [x] Remove `.pytest_cache/`
- [x] Remove `web/frontend/`
- [x] Remove `web/package-lock.json`
- [x] Remove `kotlin/parity-fixtures/inputs/mbtiles/uploads/generate_fixtures.py`

### Phase 3: File Moves ✓
- [x] Move `web/Dockerfile` to `deploy/docker/Dockerfile`
- [x] Move `web/docker-compose.yml` to `deploy/docker/docker-compose.yml`
- [x] Create `deploy/docker/README.md` from `web/README.md`
- [x] Delete `web/` directory

### Phase 4: Build Configuration Updates ✓
- [x] Update `kotlin/terrain-web-ui/build.gradle.kts` - changed frontendDistDir to `build/frontendDist`
- [x] Update `kotlin/terrain-web/build.gradle.kts` - updated run task environment variable

### Phase 5: Source Code Updates ✓
- [x] Update `kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/Dependencies.kt` - updated default paths

### Phase 6: Script Updates ✓
- [x] Update `start-web.cmd` - updated storage and frontend paths

### Phase 7: Documentation Updates ✓
- [x] Update `README.md` - removed Python references, updated paths, updated Docker command
- [x] Update `AGENTS.md` - updated Docker command path
- [x] Update `.gitignore` - removed Python/Node references, added Kotlin/JS frontend path
- [x] Update `.dockerignore` - removed web/ references
- [x] Update `kotlin/parity-fixtures/manifest.json` - removed generate_fixtures.py reference
- [x] Update `kotlin/parity-fixtures/inputs/mbtiles/uploads/README.md` - removed Python generation instructions
- [x] Update `deploy/docker/Dockerfile` - updated frontend copy path and environment variables
- [x] Update `deploy/docker/docker-compose.yml` - updated paths

### Phase 8: Verification (In Progress)
- [ ] Run `gradle :terrain-core:test`
- [ ] Run `gradle :terrain-cli:test`
- [ ] Run `gradle :terrain-web:test`
- [ ] Run `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- [ ] Run `gradle :terrain-web:installDist`
- [ ] Run `docker compose -f deploy/docker/docker-compose.yml build`

### Phase 9: Final Audit (Pending)
- [ ] Search for remaining Python references
- [ ] Search for remaining React/Vite references
- [ ] Search for remaining web/frontend references

## Blockers

_None at this time._

## Notes for Future Sessions

- All Python runtime dependencies have been removed
- The only retained implementation is Kotlin/KMP
- Docker deployment is now in `deploy/docker/`
- Frontend assets are built to `kotlin/terrain-web-ui/build/frontendDist`
- Storage root changed from `web/data` to `data`
