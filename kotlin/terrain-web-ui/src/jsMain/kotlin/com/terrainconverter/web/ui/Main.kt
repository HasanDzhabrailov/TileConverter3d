package com.terrainconverter.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.Date
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Small
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.files.File
import org.w3c.xhr.FormData
import kotlin.js.Promise

fun main() {
    renderComposable(rootElementId = "root") {
        App()
    }
}

@Composable
private fun App() {
    val state = rememberAppState()

    Div(attrs = {
        attr("class", "app-shell")
    }) {
        Div(attrs = { attr("class", "app-header") }) {
            H1 {
                Text("Terrain Converter")
            }
            P {
                Text("Browse backend jobs, inspect MBTiles server outputs, switch preview mode, and follow live conversion logs.")
            }
        }

        if (state.isLoading && state.jobs.isEmpty() && state.tilesets.isEmpty()) {
            StatusCard("Loading app state", "Requesting /api/server-info, /api/jobs, and /api/mbtiles from Kotlin/JS.")
        } else {
            Dashboard(state)
        }
    }
}

@Composable
private fun Dashboard(state: AppState) {
    Div(attrs = { attr("class", "layout") }) {
        Div(attrs = { attr("class", "left-column") }) {
            MbtilesCatalogPanel(state)
            ConvertFormPanel(state)
            JobSelectionPanel(state)
        }
        Div(attrs = { attr("class", "right-column") }) {
            Div(attrs = { attr("class", "warning-stack") }) {
                state.refreshError?.let {
                    StatusCard("Refresh warning", it)
                }
                state.jobDetailsError?.let {
                    StatusCard("Job log warning", it)
                }
                state.websocketError?.let {
                    StatusCard("WebSocket warning", it)
                }
            }
            PreviewBasePanel(state)
            MbtilesPreviewPanel(state.selectedTileset, state.previewBase)
            JobStatusPanel(state)
            DownloadPanel(state.selectedJob)
            TerrainPreviewPanel(state.selectedJob, state.previewBase)
            LogsPanel(state)
        }
    }
}

@Composable
private fun MbtilesCatalogPanel(state: AppState) {
    Panel("MBTiles server") {
        MbtilesUploadForm(state)

        H3 {
            Text("Uploaded tilesets")
        }

        if (state.tilesets.isEmpty()) {
            P {
                Text("No MBTiles servers yet.")
            }
        } else {
            Div(attrs = { attr("class", "job-list") }) {
                state.tilesets.forEach { tileset ->
                    Button(attrs = {
                        attr("class", "job-card" + if (state.selectedTilesetId == tileset.id) " selected" else "")
                        onClick { state.selectedTilesetId = tileset.id }
                    }) {
                        Span(attrs = { attr("class", "font-strong") }) {
                            Text(tileset.name ?: tileset.filename)
                        }
                        Span {
                            Text(tileset.sourceType.serializedName())
                        }
                        Small {
                            Text(formatTimestamp(tileset.createdAt))
                        }
                    }
                }
            }
        }

        state.selectedTileset?.let { tileset ->
            H3 {
                Text("Selected tileset")
            }
            DetailRow("File", tileset.filename)
            DetailRow("Type", tileset.sourceType.serializedName())
            DetailRow("Zoom", "${tileset.minzoom?.toString() ?: "-"} to ${tileset.maxzoom?.toString() ?: "-"}")
            tileset.attribution?.let {
                DetailRow("Attribution", it)
            }
            Div(attrs = { attr("class", "server-addresses") }) {
                state.serverAddresses.forEach { address ->
                    Div(attrs = { attr("class", "server-address") }) {
                        Span(attrs = { attr("class", "font-strong") }) {
                            Text(address.label)
                        }
                        P(attrs = { attr("class", "server-address-description") }) {
                            Text(address.description)
                        }
                        Div(attrs = { attr("class", "link-list") }) {
                            TemplateRow("Tiles", addressScopedUrl(address, tileset.tileUrlTemplate))
                            tileset.tilejsonUrl?.let {
                                LinkRow("TileJSON", addressScopedUrl(address, it))
                            }
                            tileset.styleUrl?.let {
                                LinkRow("Style", addressScopedUrl(address, it))
                            }
                            tileset.mobileStyleUrl?.let {
                                LinkRow("Mobile Style", addressScopedUrl(address, it))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MbtilesUploadForm(state: AppState) {
    val scope = rememberCoroutineScope()
    var sourceType by remember { mutableStateOf("auto") }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Div(attrs = {
        attr("class", "stack")
    }) {
        Label {
            Text("MBTiles file")
            Input(type = InputType.File, attrs = {
                id("mbtiles-upload-input")
                attr("accept", ".mbtiles")
                if (isSubmitting) attr("disabled", "disabled")
            })
        }
        Label {
            Text("MBTiles type")
            Select(attrs = {
                if (isSubmitting) attr("disabled", "disabled")
                onChange { sourceType = it.value ?: "auto" }
            }) {
                Option(value = "auto", attrs = { if (sourceType == "auto") attr("selected", "selected") }) { Text("Auto detect") }
                Option(value = "raster", attrs = { if (sourceType == "raster") attr("selected", "selected") }) { Text("Raster map") }
                Option(value = "raster-dem", attrs = { if (sourceType == "raster-dem") attr("selected", "selected") }) { Text("Terrain DEM") }
            }
        }
        error?.let {
            P(attrs = { attr("class", "error") }) {
                Text(it)
            }
        }
        Button(attrs = {
            if (isSubmitting) attr("disabled", "disabled")
            onClick {
                error = null

                val file = inputFiles("mbtiles-upload-input").firstOrNull()
                if (file == null) {
                    error = "Choose an MBTiles file first."
                    return@onClick
                }

                val formData = FormData()
                formData.append("mbtiles", file, file.name)
                formData.append("source_type", sourceType)

                isSubmitting = true
                scope.launch {
                    runCatching { ApiClient.uploadMbtilesTileset(formData) }
                        .onSuccess { tileset ->
                            state.mergeTileset(tileset)
                            state.selectedTilesetId = tileset.id
                            error = null
                        }
                        .onFailure {
                            error = it.message ?: "Failed to upload MBTiles"
                        }
                    isSubmitting = false
                }
            }
        }) {
            Text(if (isSubmitting) "Uploading..." else "Start tile server")
        }
    }
}

@Composable
private fun ConvertFormPanel(state: AppState) {
    val scope = rememberCoroutineScope()
    var bboxMode by remember { mutableStateOf("auto") }
    var scheme by remember { mutableStateOf("xyz") }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Panel("New conversion") {
        Div(attrs = {
            attr("class", "stack")
        }) {
            Div(attrs = { attr("class", "upload-panel stack") }) {
                H3 {
                    Text("Uploads")
                }
                Label {
                    Text("HGT files or ZIP archive")
                    Input(type = InputType.File, attrs = {
                        id("job-hgt-files-input")
                        attr("multiple", "multiple")
                        attr("accept", ".hgt,.zip")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Optional base.mbtiles")
                    Input(type = InputType.File, attrs = {
                        id("job-base-mbtiles-input")
                        attr("accept", ".mbtiles")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
            }

            Div(attrs = { attr("class", "form-grid form-grid-2") }) {
                Label {
                    Text("BBox mode")
                    Select(attrs = {
                        if (isSubmitting) attr("disabled", "disabled")
                        onChange { bboxMode = it.value ?: "auto" }
                    }) {
                        Option(value = "auto", attrs = { if (bboxMode == "auto") attr("selected", "selected") }) { Text("auto") }
                        Option(value = "manual", attrs = { if (bboxMode == "manual") attr("selected", "selected") }) { Text("manual") }
                    }
                }
                Label {
                    Text("Scheme")
                    Select(attrs = {
                        if (isSubmitting) attr("disabled", "disabled")
                        onChange { scheme = it.value ?: "xyz" }
                    }) {
                        Option(value = "xyz", attrs = { if (scheme == "xyz") attr("selected", "selected") }) { Text("xyz") }
                        Option(value = "tms", attrs = { if (scheme == "tms") attr("selected", "selected") }) { Text("tms") }
                    }
                }
            }

            if (bboxMode == "manual") {
                Div(attrs = { attr("class", "form-grid form-grid-4") }) {
                    Label {
                        Text("West")
                        Input(type = InputType.Text, attrs = {
                            id("job-west-input")
                            if (isSubmitting) attr("disabled", "disabled")
                        })
                    }
                    Label {
                        Text("South")
                        Input(type = InputType.Text, attrs = {
                            id("job-south-input")
                            if (isSubmitting) attr("disabled", "disabled")
                        })
                    }
                    Label {
                        Text("East")
                        Input(type = InputType.Text, attrs = {
                            id("job-east-input")
                            if (isSubmitting) attr("disabled", "disabled")
                        })
                    }
                    Label {
                        Text("North")
                        Input(type = InputType.Text, attrs = {
                            id("job-north-input")
                            if (isSubmitting) attr("disabled", "disabled")
                        })
                    }
                }
            }

            Div(attrs = { attr("class", "form-grid form-grid-4") }) {
                Label {
                    Text("Min zoom")
                    Input(type = InputType.Number, attrs = {
                        id("job-minzoom-input")
                        value("8")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Max zoom")
                    Input(type = InputType.Number, attrs = {
                        id("job-maxzoom-input")
                        value("12")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Tile size")
                    Input(type = InputType.Number, attrs = {
                        id("job-tile-size-input")
                        value("256")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Encoding")
                    Input(type = InputType.Text, attrs = {
                        value("mapbox")
                        attr("disabled", "disabled")
                    })
                }
            }

            error?.let {
                P(attrs = { attr("class", "error") }) {
                    Text(it)
                }
            }

            Button(attrs = {
                if (isSubmitting) attr("disabled", "disabled")
                onClick {
                    error = null

                    val hgtFiles = inputFiles("job-hgt-files-input")
                    if (hgtFiles.isEmpty()) {
                        error = "Upload at least one HGT file or ZIP archive."
                        return@onClick
                    }

                    val formData = FormData()
                    hgtFiles.forEach { file ->
                        formData.append("hgt_files", file, file.name)
                    }
                    inputFiles("job-base-mbtiles-input").firstOrNull()?.let { file ->
                        formData.append("base_mbtiles", file, file.name)
                    }
                    formData.append("bbox_mode", bboxMode)
                    formData.append("minzoom", inputValue("job-minzoom-input"))
                    formData.append("maxzoom", inputValue("job-maxzoom-input"))
                    formData.append("tile_size", inputValue("job-tile-size-input"))
                    formData.append("scheme", scheme)
                    formData.append("encoding", "mapbox")
                    if (bboxMode == "manual") {
                        formData.append("west", inputValue("job-west-input"))
                        formData.append("south", inputValue("job-south-input"))
                        formData.append("east", inputValue("job-east-input"))
                        formData.append("north", inputValue("job-north-input"))
                    }

                    isSubmitting = true
                    scope.launch {
                        runCatching { ApiClient.createJob(formData) }
                            .onSuccess { job ->
                                state.mergeJob(job)
                                state.selectedJobId = job.id
                                error = null
                            }
                            .onFailure {
                                error = it.message ?: "Failed to create job"
                            }
                        isSubmitting = false
                    }
                }
            }) {
                Text(if (isSubmitting) "Starting..." else "Start conversion")
            }
        }
    }
}

@Composable
private fun JobSelectionPanel(state: AppState) {
    Panel("Jobs") {
        if (state.jobs.isEmpty()) {
            P {
                Text("No jobs yet.")
            }
        } else {
            Div(attrs = { attr("class", "job-list") }) {
                state.jobs.forEach { job ->
                    Button(attrs = {
                        attr("class", "job-card" + if (state.selectedJobId == job.id) " selected" else "")
                        onClick { state.selectedJobId = job.id }
                    }) {
                        Span(attrs = { attr("class", "font-strong") }) {
                            Text(job.id.take(8))
                        }
                        Span {
                            Text(job.status.serializedName())
                        }
                        Small {
                            Text(formatTimestamp(job.createdAt))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBasePanel(state: AppState) {
    Panel("Preview base") {
        Div(attrs = { attr("class", "preview-base-grid") }) {
            PreviewBase.entries.forEach { previewBase ->
                Button(attrs = {
                    attr("class", if (state.previewBase == previewBase) "active-option" else "")
                    onClick { state.previewBase = previewBase }
                }) {
                    Text(previewBase.label)
                }
            }
        }
    }
}

@Composable
private fun JobStatusPanel(state: AppState) {
    Panel("Job status") {
        val job = state.selectedJob
        if (job == null) {
            P {
                Text("Select a job to inspect backend status and artifacts.")
            }
            return@Panel
        }

        Div(attrs = { attr("class", "status-row") }) {
            Span(attrs = { attr("class", "font-strong") }) {
                Text(job.status.serializedName())
            }
            job.error?.let {
                Span(attrs = { attr("class", "error") }) {
                    Text(it)
                }
            }
        }
        job.result.tileCount?.let {
            DetailRow("Tiles", it.toString())
        }
        DetailRow("Created", formatTimestamp(job.createdAt))
        DetailRow("Updated", formatTimestamp(job.updatedAt))
        DetailRow("Has base MBTiles", job.hasBaseMbtiles.toString())
        job.artifacts.tilejson?.let {
            LinkRow("TileJSON", it)
        }
        job.artifacts.stylejson?.let {
            LinkRow("Style", it)
        }
        if (state.previewBase == PreviewBase.UPLOADED && !job.hasBaseMbtiles) {
            P(attrs = { attr("class", "hint") }) {
                Text("This job has no uploaded base MBTiles, so only terrain will be shown.")
            }
        }
    }
}

@Composable
private fun DownloadPanel(job: Job?) {
    if (job == null || job.status != JobStatus.COMPLETED) {
        return
    }

    Panel("Downloads") {
        Div(attrs = { attr("class", "download-links") }) {
            job.artifacts.terrainMbtiles?.let {
                ArtifactLink("terrain-rgb.mbtiles", it)
            }
            job.artifacts.tilejson?.let {
                ArtifactLink("tiles.json", it)
            }
            job.artifacts.stylejson?.let {
                ArtifactLink("style.json", it)
            }
        }
    }
}

@Composable
private fun MbtilesPreviewPanel(tileset: MbtilesTileset?, previewBase: PreviewBase) {
    if (tileset == null) {
        return
    }

    var pitch by remember(tileset.id) { mutableStateOf(55) }
    var zoom by remember(tileset.id) { mutableStateOf<Double?>(null) }
    var mapInstance by remember(tileset.id, previewBase) { mutableStateOf<MapLibreMap?>(null) }
    val mapId = "mbtiles-preview-map"

    Panel("MBTiles preview") {
        Div(attrs = { attr("class", "preview-toolbar") }) {
            Label {
                Text("Tilt: ${if (tileset.sourceType == SourceType.RASTER_DEM) "$pitch°" else "0°"}")
                Input(type = InputType.Range, attrs = {
                    value(if (tileset.sourceType == SourceType.RASTER_DEM) pitch.toString() else "0")
                    attr("min", "0")
                    attr("max", "85")
                    if (tileset.sourceType != SourceType.RASTER_DEM) attr("disabled", "disabled")
                    onInput {
                        val value = it.value?.toString()?.toIntOrNull() ?: return@onInput
                        pitch = value
                    }
                })
            }
            Div(attrs = { attr("class", "preview-stat") }) {
                Text("Zoom: ${zoom?.let { value -> jsNumber(value) } ?: "-"}")
            }
        }
        Div(attrs = {
            id(mapId)
            attr("class", "map")
        })
    }

    DisposableEffect(tileset.id, previewBase) {
        mapInstance?.remove()
        mapInstance = null

        val container = document.getElementById(mapId)?.unsafeCast<HTMLDivElement>()
        if (container != null) {
            val map = createMapLibreMap(
                container = container,
                style = buildMbtilesPreviewStyle(tileset, previewBase),
                center = tileset.view?.let { arrayOf(it.centerLon, it.centerLat) } ?: arrayOf(0.0, 0.0),
                zoom = tileset.view?.zoom?.toDouble() ?: 1.0,
                pitch = if (tileset.sourceType == SourceType.RASTER_DEM) pitch.toDouble() else 0.0,
            )
            zoom = map.getZoom()
            map.on("zoom") {
                zoom = map.getZoom()
            }
            tileset.bounds?.let {
                map.fitBounds(
                    bounds = arrayOf(
                        arrayOf(it.west, it.south),
                        arrayOf(it.east, it.north),
                    ),
                    options = js("({ padding: 24, duration: 0 })"),
                )
                if (tileset.sourceType == SourceType.RASTER_DEM) {
                    map.setPitch(pitch.toDouble())
                }
                zoom = map.getZoom()
            }
            map.addControl(createNavigationControl(), "top-right")
            mapInstance = map
        }

        onDispose {
            mapInstance?.remove()
            mapInstance = null
        }
    }

    SideEffect {
        if (tileset.sourceType == SourceType.RASTER_DEM) {
            mapInstance?.setPitch(pitch.toDouble())
        }
    }
}

@Composable
private fun TerrainPreviewPanel(job: Job?, previewBase: PreviewBase) {
    if (job == null) {
        return
    }

    if (job.status != JobStatus.COMPLETED) {
        Panel("Preview") {
            P {
                Text("Preview becomes available after the selected job completes.")
            }
        }
        return
    }

    var pitch by remember(job.id) { mutableStateOf(55) }
    var zoom by remember(job.id) { mutableStateOf<Double?>(null) }
    var mapInstance by remember(job.id, previewBase) { mutableStateOf<MapLibreMap?>(null) }
    val mapId = "terrain-preview-map"

    Panel("Preview") {
        Div(attrs = { attr("class", "preview-toolbar") }) {
            Label {
                Text("Tilt: $pitch°")
                Input(type = InputType.Range, attrs = {
                    value(pitch.toString())
                    attr("min", "0")
                    attr("max", "85")
                    onInput {
                        val value = it.value?.toString()?.toIntOrNull() ?: return@onInput
                        pitch = value
                    }
                })
            }
            Div(attrs = { attr("class", "preview-stat") }) {
                Text("Zoom: ${zoom?.let { value -> jsNumber(value) } ?: "-"}")
            }
        }
        Div(attrs = {
            id(mapId)
            attr("class", "map")
        })
    }

    DisposableEffect(job.id, previewBase) {
        mapInstance?.remove()
        mapInstance = null

        val container = document.getElementById(mapId)?.unsafeCast<HTMLDivElement>()
        if (container != null) {
            val map = createMapLibreMap(
                container = container,
                style = buildTerrainPreviewStyle(job, previewBase),
                center = job.result.bounds?.let {
                    arrayOf((it.west + it.east) / 2.0, (it.south + it.north) / 2.0)
                } ?: arrayOf(0.0, 0.0),
                zoom = maxOf(job.options.minzoom, 8).toDouble(),
                pitch = pitch.toDouble(),
            )
            zoom = map.getZoom()
            map.on("zoom") {
                zoom = map.getZoom()
            }
            job.result.bounds?.let {
                map.fitBounds(
                    bounds = arrayOf(
                        arrayOf(it.west, it.south),
                        arrayOf(it.east, it.north),
                    ),
                    options = js("({ padding: 24, duration: 0 })"),
                )
                map.setPitch(pitch.toDouble())
                zoom = map.getZoom()
            }
            map.addControl(createNavigationControl(), "top-right")
            mapInstance = map
        }

        onDispose {
            mapInstance?.remove()
            mapInstance = null
        }
    }

    SideEffect {
        mapInstance?.setPitch(pitch.toDouble())
    }
}

@Composable
private fun LogsPanel(state: AppState) {
    Panel("Live logs") {
        Pre(attrs = { attr("class", "logs") }) {
            Text(if (state.logs.isEmpty()) "" else state.logs.joinToString("\n"))
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Div(attrs = { attr("class", "panel stack") }) {
        H3 {
            Text(title)
        }
        content()
    }
}

@Composable
private fun StatusCard(title: String, message: String) {
    Div(attrs = { attr("class", "panel warning-panel") }) {
        H2 {
            Text(title)
        }
        P {
            Text(message)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Div(attrs = { attr("class", "detail-row") }) {
        Span(attrs = { attr("class", "detail-label") }) {
            Text(label)
        }
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("/")) {
            Code {
                Text(value)
            }
        } else {
            Text(value)
        }
    }
}

@Composable
private fun LinkRow(label: String, href: String) {
    val absoluteHref = ApiClient.absoluteUrl(href)

    Div(attrs = { attr("class", "detail-row") }) {
        Span(attrs = { attr("class", "detail-label") }) {
            Text(label)
        }
        Div(attrs = { attr("class", "template-row") }) {
            A(href = absoluteHref, attrs = {
                attr("target", "_blank")
                attr("rel", "noreferrer")
            }) {
                Text(absoluteHref)
            }
            CopyButton(absoluteHref)
        }
    }
}

@Composable
private fun TemplateRow(label: String, value: String) {
    val absoluteValue = ApiClient.absoluteUrl(value)
    var copyStatus by remember { mutableStateOf<String?>(null) }

    Div(attrs = { attr("class", "detail-row") }) {
        Span(attrs = { attr("class", "detail-label") }) {
            Text(label)
        }
        Div(attrs = { attr("class", "template-row") }) {
            Code {
                Text(absoluteValue)
            }
            CopyButton(absoluteValue) { copyStatus = it }
        }
        copyStatus?.let {
            Small(attrs = { attr("class", if (it == "Copied") "muted" else "error") }) {
                Text(it)
            }
        }
    }
}

@Composable
private fun ArtifactLink(label: String, href: String) {
    val absoluteHref = ApiClient.absoluteUrl(href)

    Div(attrs = { attr("class", "artifact-link") }) {
        A(href = absoluteHref, attrs = {
            attr("target", "_blank")
            attr("rel", "noreferrer")
        }) {
            Text(label)
        }
        CopyButton(absoluteHref)
    }
}

@Composable
private fun CopyButton(value: String, onStatusChange: ((String?) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var ownStatus by remember { mutableStateOf<String?>(null) }
    val status: (String?) -> Unit = onStatusChange ?: { value -> ownStatus = value }

    Button(attrs = {
        attr("class", "copy-button")
        onClick {
            scope.launch {
                val copied = copyTextToClipboard(value)
                status(if (copied) "Copied" else "Copy failed")
                window.setTimeout({ status(null) }, 2000)
            }
        }
    }) {
        Text(ownStatus ?: "Copy")
    }
}

private fun formatTimestamp(value: String): String = Date(value).toLocaleString()

private fun PreviewBase.serializedName(): String = when (this) {
    PreviewBase.OSM -> "osm"
    PreviewBase.UPLOADED -> "uploaded"
    PreviewBase.NONE -> "none"
}

private val PreviewBase.label: String
    get() = when (this) {
        PreviewBase.OSM -> "OpenStreetMap"
        PreviewBase.UPLOADED -> "Uploaded base"
        PreviewBase.NONE -> "None"
    }

private fun JobStatus.serializedName(): String = when (this) {
    JobStatus.PENDING -> "pending"
    JobStatus.RUNNING -> "running"
    JobStatus.COMPLETED -> "completed"
    JobStatus.FAILED -> "failed"
}

private fun SourceType.serializedName(): String = when (this) {
    SourceType.RASTER -> "raster"
    SourceType.RASTER_DEM -> "raster-dem"
}

private fun addressScopedUrl(address: ServerAddress, path: String): String {
    return ApiClient.absoluteUrl(address.baseUrl + path)
}

private fun inputValue(id: String): String {
    val input = document.getElementById(id)?.unsafeCast<HTMLInputElement>()
    return input?.value.orEmpty()
}

private fun inputFiles(id: String): List<File> {
    val input = document.getElementById(id)?.unsafeCast<HTMLInputElement>() ?: return emptyList()
    val files = input.files ?: return emptyList()
    val result = mutableListOf<File>()
    for (index in 0 until files.length) {
        files.item(index)?.let(result::add)
    }
    return result
}

internal fun buildTerrainPreviewStyle(job: Job, previewBase: PreviewBase): dynamic {
    val sources = js("({})")
    sources.terrain = js("({})")
    sources.terrain.type = "raster-dem"
    sources.terrain.tiles = arrayOf("/api/jobs/${job.id}/terrain/{z}/{x}/{y}.png")
    sources.terrain.encoding = job.options.encoding.serializedName()
    sources.terrain.tileSize = job.options.tileSize
    sources.terrain.scheme = job.options.scheme.serializedName()

    val layers = mutableListOf<dynamic>()
    layers.add(backgroundLayer())

    if (previewBase == PreviewBase.OSM) {
        sources.osm = js("({})")
        sources.osm.type = "raster"
        sources.osm.tiles = arrayOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
        sources.osm.tileSize = 256
        sources.osm.attribution = "&copy; OpenStreetMap contributors"
        layers.add(rasterLayer(id = "osm", source = "osm"))
    }

    if (previewBase == PreviewBase.UPLOADED && job.hasBaseMbtiles) {
        sources["uploaded-base"] = js("({})")
        sources["uploaded-base"].type = "raster"
        sources["uploaded-base"].tiles = arrayOf("/api/jobs/${job.id}/base/{z}/{x}/{y}")
        sources["uploaded-base"].tileSize = 256
        layers.add(rasterLayer(id = "uploaded-base", source = "uploaded-base"))
    }

    layers.add(hillshadeLayer(id = "terrain-hillshade", source = "terrain"))

    val style = js("({})")
    style.version = 8
    style.sources = sources
    style.layers = layers.toTypedArray()
    style.terrain = js("({ source: 'terrain', exaggeration: 1.15 })")
    return style
}

private fun buildMbtilesPreviewStyle(tileset: MbtilesTileset, previewBase: PreviewBase): dynamic {
    val sources = js("({})")
    sources["mbtiles-raster"] = js("({})")
    sources["mbtiles-raster"].type = tileset.sourceType.serializedName()
    sources["mbtiles-raster"].tiles = arrayOf(tileset.tileUrlTemplate)
    sources["mbtiles-raster"].tileSize = 256
    if (tileset.sourceType == SourceType.RASTER_DEM) {
        sources["mbtiles-raster"].encoding = "mapbox"
    }

    val layers = mutableListOf<dynamic>()
    layers.add(backgroundLayer())

    if (previewBase == PreviewBase.OSM) {
        sources.osm = js("({})")
        sources.osm.type = "raster"
        sources.osm.tiles = arrayOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
        sources.osm.tileSize = 256
        sources.osm.attribution = "&copy; OpenStreetMap contributors"
        layers.add(rasterLayer(id = "osm", source = "osm"))
    }

    val style = js("({})")
    style.version = 8
    style.sources = sources
    if (tileset.sourceType == SourceType.RASTER_DEM) {
        layers.add(hillshadeLayer(id = "mbtiles-hillshade", source = "mbtiles-raster"))
        style.layers = layers.toTypedArray()
        style.terrain = js("({ source: 'mbtiles-raster', exaggeration: 1.15 })")
    } else {
        layers.add(rasterLayer(id = "mbtiles-raster", source = "mbtiles-raster"))
        style.layers = layers.toTypedArray()
    }
    return style
}

private fun backgroundLayer(): dynamic {
    val paint = js("({})")
    paint["background-color"] = "#dbeafe"

    val layer = js("({})")
    layer.id = "background"
    layer.type = "background"
    layer.paint = paint
    return layer
}

private fun rasterLayer(id: String, source: String): dynamic {
    val layer = js("({})")
    layer.id = id
    layer.type = "raster"
    layer.source = source
    return layer
}

private fun hillshadeLayer(id: String, source: String): dynamic {
    val paint = js("({})")
    paint["hillshade-shadow-color"] = "#1e293b"
    paint["hillshade-highlight-color"] = "#f8fafc"
    paint["hillshade-accent-color"] = "#94a3b8"
    paint["hillshade-exaggeration"] = 0.8

    val layer = js("({})")
    layer.id = id
    layer.type = "hillshade"
    layer.source = source
    layer.paint = paint
    return layer
}

private fun TerrainEncoding.serializedName(): String = when (this) {
    TerrainEncoding.MAPBOX -> "mapbox"
}

private fun TileScheme.serializedName(): String = when (this) {
    TileScheme.XYZ -> "xyz"
    TileScheme.TMS -> "tms"
}

private suspend fun copyTextToClipboard(value: String): Boolean {
    val isSecureContext = window.asDynamic().isSecureContext as? Boolean ?: false
    if (!isSecureContext && copyTextWithTextarea(value)) return true

    val clipboard = window.navigator.asDynamic().clipboard ?: return false
    return runCatching {
        clipboard.writeText(value).unsafeCast<Promise<Unit>>().await()
    }.isSuccess || copyTextWithTextarea(value)
}

private fun copyTextWithTextarea(value: String): Boolean {
    val body = document.body ?: return false
    val textarea = document.createElement("textarea").unsafeCast<HTMLTextAreaElement>()
    textarea.value = value
    textarea.setAttribute("readonly", "readonly")
    textarea.style.position = "fixed"
    textarea.style.left = "-9999px"
    body.appendChild(textarea)
    textarea.select()
    val copied = runCatching { document.asDynamic().execCommand("copy") as Boolean }.getOrDefault(false)
    body.removeChild(textarea)
    return copied
}

private fun jsNumber(value: Double): String = value.asDynamic().toFixed(2) as String
