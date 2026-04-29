# Backend HTTP Requests

This directory records the canonical request shapes exercised by the backend parity harness.

Current saved request classes:

- `GET /api/health`
- `GET /api/jobs`
- `POST /api/jobs` with invalid `minzoom`
- `POST /api/jobs` with one uploaded HGT file

The current executable parity tests synthesize multipart payloads in Kotlin so request bodies remain cross-platform and easy to inspect.
