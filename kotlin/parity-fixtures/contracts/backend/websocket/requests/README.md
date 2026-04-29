# Backend WebSocket Requests

This directory records the canonical WebSocket subscription shapes exercised by the parity harness.

Current saved request classes:

- `WS /ws/jobs/job-fixed`
- `WS /ws/jobs/job-fixed-fail`

The executable parity tests connect directly through Ktor's test host and compare saved event transcripts.
