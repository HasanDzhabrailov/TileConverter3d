# KMP-Only Cleanup Plan

**Goal**: Remove all Python and legacy frontend code, leaving only Kotlin/KMP implementation.

## Required Removals

1. **Python Archive**: `archive/legacy-python/` - Entire Python codebase
2. **Python Cache**: `.pytest_cache/` - Python test cache
3. **Old Frontend**: `web/frontend/` - React/Vite frontend
4. **Package Lock**: `web/package-lock.json` - NPM lock file
5. **Python Script**: `kotlin/parity-fixtures/inputs/mbtiles/uploads/generate_fixtures.py`

## Required Moves

1. `web/Dockerfile` → `deploy/docker/Dockerfile`
2. `web/docker-compose.yml` → `deploy/docker/docker-compose.yml`
3. `web/README.md` content → `deploy/docker/README.md` (migrate and update)

## Required Updates

### Build Configuration

1. **`kotlin/terrain-web-ui/build.gradle.kts`**
   - Change `frontendDistDir` from `../../web/frontend/dist` to `build/frontendDist`
   - Update `syncFrontendDist` task description

2. **`kotlin/terrain-web/build.gradle.kts`**
   - Update `run` task environment variable to use new frontend path
   - Update `syncFrontendDist` task reference

### Source Code

3. **`kotlin/terrain-web/src/main/kotlin/com/terrainconverter/web/Dependencies.kt`**
   - Update default `frontendDist` path from `web/frontend/dist` to new location

### Scripts

4. **`start-web.cmd`**
   - Update `TERRAIN_WEB_FRONTEND_DIST` path

### Documentation

5. **`README.md`** (root)
   - Remove Python/archive references
   - Update Docker command to use `deploy/docker/docker-compose.yml`
   - Update frontend dist path references
   - Remove legacy Python mentions

6. **`AGENTS.md`**
   - Update Docker command path
   - Remove Python-related constraints

7. **`web/README.md`** → Delete after migrating content

8. **`.gitignore`**
   - Remove `web/frontend/dist/` entry
   - Add new frontend dist location

9. **`.dockerignore`**
   - Update paths as needed

### Fixture Files

10. **`kotlin/parity-fixtures/manifest.json`**
    - Remove or update fixture entries referencing `generate_fixtures.py`

11. **`kotlin/parity-fixtures/inputs/mbtiles/uploads/README.md`**
    - Remove Python generation instructions

### New Files

12. **`deploy/docker/README.md`**
    - Docker deployment documentation

## Docker Configuration Updates

### `deploy/docker/Dockerfile`

- Update `COPY` path for frontend assets from `/app/web/frontend/dist` to `/app/terrain-web-ui`
- Update `ENV TERRAIN_WEB_FRONTEND_DIST` to `/app/terrain-web-ui`

### `deploy/docker/docker-compose.yml`

- Update `dockerfile` path reference
- Update `TERRAIN_WEB_FRONTEND_DIST` environment variable

## Path Changes Summary

| Old Path | New Path |
|----------|----------|
| `web/frontend/dist` | `kotlin/terrain-web-ui/build/frontendDist` |
| `web/Dockerfile` | `deploy/docker/Dockerfile` |
| `web/docker-compose.yml` | `deploy/docker/docker-compose.yml` |
| `/app/web/frontend/dist` (Docker) | `/app/terrain-web-ui` |

## Verification Steps

1. Run `gradle :terrain-core:test`
2. Run `gradle :terrain-cli:test`
3. Run `gradle :terrain-web:test`
4. Run `gradle -p kotlin/terrain-web-ui syncFrontendDist`
5. Run `gradle :terrain-web:installDist`
6. Run `docker compose -f deploy/docker/docker-compose.yml build`

## Final Audit

Search for remaining references to:
- `python`, `pytest`, `pyproject`, `pip`
- `FastAPI`, `uvicorn`
- `archive/legacy-python`
- `React`, `react`, `vite`, `tsx`, `jsx`
- `web/frontend`
- `package-lock`

## Target Final Structure

```
terrain-converter-project/
├── kotlin/
│   ├── terrain-core/
│   ├── terrain-cli/
│   ├── terrain-web/
│   └── terrain-web-ui/
├── deploy/
│   └── docker/
│       ├── Dockerfile
│       ├── docker-compose.yml
│       └── README.md
├── docs/
│   ├── kmp-only-cleanup-plan.md
│   ├── kmp-only-cleanup-status.md
│   └── ...
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── AGENTS.md
├── .gitignore
├── .dockerignore
└── start-web.cmd
```
