from __future__ import annotations

import threading
import uuid
from datetime import datetime, timezone

from .config import Settings
from .converter_runner import run_conversion
from .models import BBox, JobArtifacts, JobDetail, JobOptions, JobResult, JobStatus, JobSummary
from .storage import Storage
from .websocket_manager import WebSocketManager


class JobManager:
    def __init__(self, settings: Settings, storage: Storage, websocket_manager: WebSocketManager) -> None:
        self.settings = settings
        self.storage = storage
        self.websocket_manager = websocket_manager
        self._lock = threading.Lock()
        self._jobs: dict[str, JobDetail] = {}

    def create_job(self, options: JobOptions, has_base_mbtiles: bool) -> JobDetail:
        now = datetime.now(timezone.utc)
        job = JobDetail(
            id=uuid.uuid4().hex,
            status=JobStatus.pending,
            created_at=now,
            updated_at=now,
            options=options,
            has_base_mbtiles=has_base_mbtiles,
            artifacts=JobArtifacts(),
            result=JobResult(),
        )
        self.storage.paths_for(job.id)
        with self._lock:
            self._jobs[job.id] = job
        self._broadcast_job(job)
        return job

    def start_job(self, job_id: str, base_url: str) -> None:
        thread = threading.Thread(target=self._run_job, args=(job_id, base_url), daemon=True)
        thread.start()

    def list_jobs(self) -> list[JobSummary]:
        with self._lock:
            return [JobSummary(**job.model_dump(exclude={"logs"})) for job in sorted(self._jobs.values(), key=lambda item: item.created_at, reverse=True)]

    def get_job(self, job_id: str) -> JobDetail:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                raise KeyError(job_id)
            return job

    def append_log(self, job_id: str, line: str) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.logs.append(line)
            job.updated_at = datetime.now(timezone.utc)
        self.websocket_manager.broadcast(job_id, {"type": "log", "line": line})

    def update_status(self, job_id: str, status: JobStatus, *, error: str | None = None) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.status = status
            job.error = error
            job.updated_at = datetime.now(timezone.utc)
        self._broadcast_job(self.get_job(job_id))

    def complete_job(self, job_id: str, *, bounds: dict[str, float], tile_count: int, base_url: str) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.status = JobStatus.completed
            job.updated_at = datetime.now(timezone.utc)
            job.result = JobResult(bounds=BBox(**bounds), tile_count=tile_count)
            job.artifacts = JobArtifacts(
                terrain_mbtiles=f"/api/jobs/{job_id}/downloads/terrain-rgb.mbtiles",
                tilejson=f"/api/jobs/{job_id}/downloads/tiles.json",
                stylejson=f"/api/jobs/{job_id}/downloads/style.json",
                terrain_tile_url_template=f"/api/jobs/{job_id}/terrain/{{z}}/{{x}}/{{y}}.png",
                public_terrain_tile_url_template=f"{base_url}/api/jobs/{job_id}/terrain/{{z}}/{{x}}/{{y}}.png",
                public_tilejson=f"{base_url}/api/jobs/{job_id}/tilejson",
                public_stylejson=f"{base_url}/api/jobs/{job_id}/style",
            )
        self._broadcast_job(self.get_job(job_id))

    def _run_job(self, job_id: str, base_url: str) -> None:
        self.update_status(job_id, JobStatus.running)
        paths = self.storage.paths_for(job_id)
        options = self.get_job(job_id).options
        try:
            result = run_conversion(
                settings=self.settings,
                job_id=job_id,
                options=options,
                paths=paths,
                base_url=base_url,
                log=lambda line: self.append_log(job_id, line),
            )
            self.complete_job(job_id, bounds=result["bounds"], tile_count=result["tile_count"], base_url=base_url)
        except Exception as exc:
            self.append_log(job_id, f"ERROR: {exc}")
            self.update_status(job_id, JobStatus.failed, error=str(exc))

    def _broadcast_job(self, job: JobDetail) -> None:
        self.websocket_manager.broadcast(job.id, {"type": "job", "job": job.model_dump(mode="json")})
