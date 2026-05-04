package com.terrainconverter.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

enum class PreviewBase {
    OSM,
    UPLOADED,
    NONE,
}

class AppState {
    var isLoading by mutableStateOf(true)
    var jobs by mutableStateOf<List<Job>>(emptyList())
    var serverAddresses by mutableStateOf<List<ServerAddress>>(emptyList())
    var tilesets by mutableStateOf<List<MbtilesTileset>>(emptyList())
    var selectedJobId by mutableStateOf<String?>(null)
    var selectedTilesetId by mutableStateOf<String?>(null)
    var previewBase by mutableStateOf(PreviewBase.OSM)
    var logs by mutableStateOf<List<String>>(emptyList())
    var refreshError by mutableStateOf<String?>(null)
    var jobDetailsError by mutableStateOf<String?>(null)
    var websocketError by mutableStateOf<String?>(null)

    val selectedJob: Job?
        get() = jobs.firstOrNull { it.id == selectedJobId }

    val selectedTileset: MbtilesTileset?
        get() = tilesets.firstOrNull { it.id == selectedTilesetId }

    fun applyBootstrap(data: BootstrapData) {
        if (jobs != data.jobs) {
            jobs = data.jobs
        }
        if (tilesets != data.tilesets) {
            tilesets = data.tilesets
        }
        if (serverAddresses != data.serverInfo.addresses) {
            serverAddresses = data.serverInfo.addresses
        }
        if (selectedJobId == null && data.jobs.isNotEmpty()) {
            selectedJobId = data.jobs.first().id
        }
        if (selectedTilesetId == null && data.tilesets.isNotEmpty()) {
            selectedTilesetId = data.tilesets.first().id
        }
    }

    fun mergeJob(job: Job) {
        jobs = listOf(job) + jobs.filterNot { it.id == job.id }
    }

    fun mergeTileset(tileset: MbtilesTileset) {
        tilesets = listOf(tileset) + tilesets.filterNot { it.id == tileset.id }
    }
}

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
