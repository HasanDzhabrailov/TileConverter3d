# Legacy Python Code Archive

**Status:** Archived - No longer supported for runtime use

**Date Archived:** 2026-04-29

## Contents

This directory contains the legacy Python implementation of the terrain converter system, preserved for reference only.

### `converter/`

The original Python-based terrain converter CLI and core library:

- `terrain_converter/` - Core conversion logic (HGT parsing, Terrain-RGB encoding, MBTiles writing)
- `tests/` - Python test suite
- `pyproject.toml` - Python packaging manifest

**Note:** The Kotlin/KMP implementation in `kotlin/terrain-core/` and `kotlin/terrain-cli/` is now the canonical runtime.

### `backend/`

The original FastAPI-based web backend:

- `app/` - FastAPI application (main.py, jobs.py, websocket_manager.py, etc.)
- `tests/` - Python backend tests
- `pyproject.toml` - Python packaging manifest

**Note:** The Kotlin/Ktor implementation in `kotlin/terrain-web/` is now the canonical backend runtime.

## Why Archived

The project has completed migration to Kotlin/KMP as documented in:

- `docs/kotlin-migration-plan.md`
- `docs/kotlin-migration-status.md`
- `docs/reviews/kotlin-terrain-backend-review.md`

## Runtime Status

- ❌ **Python runtime is NOT supported**
- ✅ Use `kotlin/terrain-cli/` for CLI operations
- ✅ Use `kotlin/terrain-web/` for backend operations
- ✅ Use `web/docker-compose.yml` for containerized deployment (Kotlin-only)

## If You Need to Reference This Code

The archived code is preserved for historical reference and debugging purposes only. Do not:

- Install or run the Python packages
- Modify the Python code expecting it to be used in production
- Submit PRs against the Python code

For all runtime operations, use the Kotlin implementations.
