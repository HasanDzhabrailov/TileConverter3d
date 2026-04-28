from __future__ import annotations

import asyncio
from collections import defaultdict

from fastapi import WebSocket


class WebSocketManager:
    def __init__(self) -> None:
        self._connections: dict[str, set[WebSocket]] = defaultdict(set)
        self._loop: asyncio.AbstractEventLoop | None = None

    async def connect(self, job_id: str, websocket: WebSocket) -> None:
        await websocket.accept()
        self._loop = asyncio.get_running_loop()
        self._connections[job_id].add(websocket)

    def disconnect(self, job_id: str, websocket: WebSocket) -> None:
        if job_id in self._connections:
            self._connections[job_id].discard(websocket)
            if not self._connections[job_id]:
                self._connections.pop(job_id, None)

    def broadcast(self, job_id: str, payload: dict[str, object]) -> None:
        sockets = list(self._connections.get(job_id, set()))
        if not sockets or self._loop is None:
            return
        asyncio.run_coroutine_threadsafe(self._broadcast_async(job_id, payload), self._loop)

    async def _broadcast_async(self, job_id: str, payload: dict[str, object]) -> None:
        for websocket in list(self._connections.get(job_id, set())):
            try:
                await websocket.send_json(payload)
            except Exception:
                self.disconnect(job_id, websocket)
