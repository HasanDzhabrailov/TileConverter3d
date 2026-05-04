package com.terrainconverter.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class JobManager(
    private val dependencies: AppDependencies,
    private val storage: Storage,
    private val websocketManager: WebSocketManager,
    private val scope: CoroutineScope,
) {
    private val jobs = ConcurrentHashMap<String, JobDetail>()

    fun createJob(options: JobOptions, hasBaseMbtiles: Boolean): JobDetail {
        val now = dependencies.now()
        val job = JobDetail(
            id = dependencies.jobIdProvider(),
            status = JobStatus.PENDING,
            createdAt = now,
            updatedAt = now,
            options = options,
            hasBaseMbtiles = hasBaseMbtiles,
        )
        storage.pathsFor(job.id)
        jobs[job.id] = job
        scope.launch { websocketManager.broadcastJob(job) }
        return job
    }

    fun startJob(jobId: String, baseUrl: String) {
        dependencies.launcher(scope) { runJob(jobId, baseUrl) }
    }

    fun listJobs(): List<JobSummary> = jobs.values
        .sortedByDescending { it.createdAt }
        .map { JobSummary(it.id, it.status, it.createdAt, it.updatedAt, it.options, it.hasBaseMbtiles, it.artifacts, it.result, it.error) }

    fun getJob(jobId: String): JobDetail = jobs[jobId] ?: throw NoSuchElementException(jobId)

    fun appendLog(jobId: String, line: String) {
        val current = getJob(jobId)
        val updated = current.copy(logs = current.logs + line, updatedAt = dependencies.now())
        jobs[jobId] = updated
        scope.launch { websocketManager.broadcastLog(jobId, line) }
    }

    fun updateStatus(jobId: String, status: JobStatus, error: String? = null) {
        val current = getJob(jobId)
        val updated = current.copy(status = status, error = error, updatedAt = dependencies.now())
        jobs[jobId] = updated
        scope.launch { websocketManager.broadcastJob(updated) }
    }

    fun completeJob(jobId: String, bounds: BBox, tileCount: Int, baseUrl: String) {
        val current = getJob(jobId)
        val updated = current.copy(
            status = JobStatus.COMPLETED,
            updatedAt = dependencies.now(),
            result = JobResult(bounds = bounds, tileCount = tileCount),
            artifacts = JobArtifacts(
                terrainMbtiles = "/api/jobs/$jobId/downloads/terrain-rgb.mbtiles",
                tilejson = "/api/jobs/$jobId/downloads/tiles.json",
                stylejson = "/api/jobs/$jobId/downloads/style.json",
                terrainTileUrlTemplate = "/api/jobs/$jobId/terrain/{z}/{x}/{y}.png",
                publicTerrainTileUrlTemplate = "$baseUrl/api/jobs/$jobId/terrain/{z}/{x}/{y}.png",
                publicTilejson = "$baseUrl/api/jobs/$jobId/tilejson",
                publicStylejson = "$baseUrl/api/jobs/$jobId/style",
            ),
        )
        jobs[jobId] = updated
        scope.launch { websocketManager.broadcastJob(updated) }
    }

    private suspend fun runJob(jobId: String, baseUrl: String) {
        updateStatus(jobId, JobStatus.RUNNING)
        val paths = storage.pathsFor(jobId)
        val options = getJob(jobId).options
        try {
            val result = dependencies.conversionRunner(
                ConversionRequest(
                    settings = dependencies.settings,
                    jobId = jobId,
                    options = options,
                    paths = paths,
                    baseUrl = baseUrl,
                    log = { appendLog(jobId, it) }
                )
            )
            completeJob(jobId, result.bounds, result.tileCount, baseUrl)
        } catch (error: Exception) {
            appendLog(jobId, "ERROR: ${error.message ?: error.javaClass.name}")
            updateStatus(jobId, JobStatus.FAILED, error.message ?: error.javaClass.name)
        }
    }
}
