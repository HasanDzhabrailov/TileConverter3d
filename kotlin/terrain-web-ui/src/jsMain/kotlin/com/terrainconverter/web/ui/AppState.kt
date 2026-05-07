package com.terrainconverter.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.await

enum class PreviewMode {
    TWO_D,
    THREE_D,
}

class AppState {
    var isLoading by mutableStateOf(true)
    var jobs by mutableStateOf<List<Job>>(emptyList())
    var serverAddresses by mutableStateOf<List<ServerAddress>>(emptyList())
    var tilesets by mutableStateOf<List<MbtilesTileset>>(emptyList())
    var baseSources by mutableStateOf<List<BaseMapSource>>(emptyList())
    var storageStats by mutableStateOf<StorageStats?>(null)
    var selectedJobId by mutableStateOf<String?>(null)
    var selectedTilesetId by mutableStateOf<String?>(null)
    var selectedBaseSourceId by mutableStateOf("openstreetmap")
    var previewMode by mutableStateOf(PreviewMode.TWO_D)
    var twoDPreviewTilesetId by mutableStateOf<String?>(null)
    var terrainPreviewSource by mutableStateOf<TerrainPreviewSource?>(null)
    var previewNotice by mutableStateOf<String?>(null)
    var logs by mutableStateOf<List<String>>(emptyList())
    var refreshError by mutableStateOf<String?>(null)
    var jobDetailsError by mutableStateOf<String?>(null)
    var websocketError by mutableStateOf<String?>(null)
    var activeMobileAddress by mutableStateOf<ServerAddress?>(null)

    val selectedJob: Job?
        get() = jobs.firstOrNull { it.id == selectedJobId }

    val selectedTileset: MbtilesTileset?
        get() = tilesets.firstOrNull { it.id == selectedTilesetId }

    val twoDPreviewTileset: MbtilesTileset?
        get() = tilesets.firstOrNull { it.id == twoDPreviewTilesetId }

    val selectedBaseSource: BaseMapSource
        get() = baseSources.firstOrNull { it.id == selectedBaseSourceId }
            ?: baseSources.firstOrNull { it.id == "openstreetmap" }
            ?: fallbackOpenStreetMapSource

    fun applyBootstrap(data: BootstrapData) {
        val previousStatuses = jobs.associate { it.id to it.status }
        if (jobs != data.jobs) {
            jobs = data.jobs
        }
        if (tilesets != data.tilesets) {
            tilesets = data.tilesets
        }
        if (serverAddresses != data.serverInfo.addresses) {
            serverAddresses = data.serverInfo.addresses
        }
        if (baseSources != data.baseSources) {
            baseSources = data.baseSources
        }
        storageStats = data.storageStats
        if (baseSources.none { it.id == selectedBaseSourceId }) {
            selectedBaseSourceId = baseSources.firstOrNull { it.id == "openstreetmap" }?.id ?: "openstreetmap"
        }
        if (selectedJobId != null && data.jobs.none { it.id == selectedJobId }) selectedJobId = data.jobs.firstOrNull()?.id
        if (selectedTilesetId != null && data.tilesets.none { it.id == selectedTilesetId }) selectedTilesetId = null
        if (twoDPreviewTilesetId != null && data.tilesets.none { it.id == twoDPreviewTilesetId }) twoDPreviewTilesetId = null
        terrainPreviewSource = when (val source = terrainPreviewSource) {
            is TerrainPreviewSource.Job -> source.takeIf { data.jobs.any { it.id == source.jobId } }
            is TerrainPreviewSource.MbtilesDem -> source.takeIf { data.tilesets.any { it.id == source.tilesetId } }
            null -> null
        }
        if (selectedJobId == null && data.jobs.isNotEmpty()) {
            selectedJobId = data.jobs.first().id
        }
        data.jobs.firstOrNull { job ->
            job.status == JobStatus.COMPLETED && previousStatuses[job.id]?.let { it != JobStatus.COMPLETED } == true
        }?.let(::selectCompletedJobForPreview)
    }

    suspend fun detectWorkingAddress() {
        val mobileAddressOrder = listOf("public", "lan-primary", "lan-domain", "request-host", "local-domain")
        val lanAddresses = serverAddresses
            .filter {
                it.id in mobileAddressOrder || it.id.startsWith("lan-alt-")
            }
            .sortedBy {
                val index = mobileAddressOrder.indexOf(it.id)
                if (index >= 0) index else mobileAddressOrder.size
            }
        
        for (address in lanAddresses) {
            try {
                val response = kotlinx.browser.window.fetch("${address.baseUrl}/api/health").await()
                if (response.status == 200.toShort()) {
                    activeMobileAddress = address
                    return
                }
            } catch (_: Throwable) {
            }
        }
        
        activeMobileAddress = lanAddresses.firstOrNull()
    }

    fun mergeJob(job: Job) {
        val previousStatus = jobs.firstOrNull { it.id == job.id }?.status
        jobs = listOf(job) + jobs.filterNot { it.id == job.id }
        if (job.status == JobStatus.COMPLETED && previousStatus != JobStatus.COMPLETED) {
            selectCompletedJobForPreview(job)
        }
    }

    fun mergeTileset(tileset: MbtilesTileset) {
        tilesets = listOf(tileset) + tilesets.filterNot { it.id == tileset.id }
    }

    fun selectJob(job: Job) {
        selectedJobId = job.id
        if (job.status == JobStatus.COMPLETED) {
            twoDPreviewTilesetId = null
            terrainPreviewSource = TerrainPreviewSource.Job(job.id)
            if (previewMode == PreviewMode.THREE_D) {
                previewNotice = null
            }
        }
    }

    fun openTilesetIn2D(tileset: MbtilesTileset) {
        selectedTilesetId = tileset.id
        twoDPreviewTilesetId = tileset.id
        previewMode = PreviewMode.TWO_D
        previewNotice = null
    }

    fun openTilesetIn3D(tileset: MbtilesTileset) {
        selectedTilesetId = tileset.id
        terrainPreviewSource = TerrainPreviewSource.MbtilesDem(tileset.id)
        previewMode = PreviewMode.THREE_D
        previewNotice = "Рельеф DEM выбран. 3D просмотр включён."
    }

    private fun selectCompletedJobForPreview(job: Job) {
        selectedJobId = job.id
        twoDPreviewTilesetId = null
        terrainPreviewSource = TerrainPreviewSource.Job(job.id)
        previewMode = PreviewMode.THREE_D
        previewNotice = "Рельеф готов. 3D просмотр включён."
    }
}

val fallbackOpenStreetMapSource = BaseMapSource(
    id = "openstreetmap",
    name = "OpenStreetMap",
    urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    attribution = "&copy; OpenStreetMap contributors",
    maxZoom = 19,
    isBuiltin = true,
)

@Composable
fun rememberAppState(): AppState {
    val state = remember { AppState() }

    LaunchedEffect(Unit) {
        suspend fun load() {
            runCatching { ApiClient.loadBootstrap() }
                .onSuccess {
                    state.applyBootstrap(it)
                    state.isLoading = false
                    state.refreshError = null
                    state.detectWorkingAddress()
                }
                .onFailure {
                    state.isLoading = false
                    state.refreshError = it.message ?: it.toString()
                }
        }

        load()
        while (true) {
            delay(5_000)
            load()
        }
    }

    LaunchedEffect(state.selectedJobId) {
        val jobId = state.selectedJobId ?: run {
            state.logs = emptyList()
            state.jobDetailsError = null
            state.websocketError = null
            return@LaunchedEffect
        }

        state.logs = emptyList()
        state.jobDetailsError = null
        state.websocketError = null

        runCatching { ApiClient.getJob(jobId) }
            .onSuccess { job ->
                if (state.selectedJobId == jobId) {
                    state.logs = job.logs
                }
            }
            .onFailure {
                if (state.selectedJobId == jobId) {
                    state.jobDetailsError = it.message ?: it.toString()
                }
            }
    }

    DisposableEffect(state.selectedJobId) {
        val jobId = state.selectedJobId
        if (jobId == null) {
            onDispose { }
        } else {
            val socket = ApiClient.connectJob(
                jobId = jobId,
                onEvent = { event ->
                    when (event.type) {
                        "log" -> event.line?.let { line ->
                            if (state.selectedJobId == jobId) {
                                state.logs = state.logs + line
                            }
                        }

                        "job" -> event.job?.let(state::mergeJob)
                    }
                },
                onError = {
                    if (state.selectedJobId == jobId) {
                        state.websocketError = it.message ?: it.toString()
                    }
                },
            )

            onDispose {
                socket.close()
            }
        }
    }

    return state
}
