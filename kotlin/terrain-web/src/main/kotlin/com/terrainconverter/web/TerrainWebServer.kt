package com.terrainconverter.web

import com.terrainconverter.core.Bounds
import com.terrainconverter.core.ConversionOptions
import com.terrainconverter.core.buildStyle
import com.terrainconverter.core.buildTileJson
import com.terrainconverter.core.renderPrettyJson
import com.terrainconverter.core.runConversion as runTerrainConversion
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class Settings(
    val appName: String = "terrain-converter-web",
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val storageRoot: Path = Path.of("web", "data"),
    val frontendDist: Path = Path.of("web", "frontend", "dist"),
) {
    companion object {
        fun fromEnv(): Settings = Settings(
            appName = System.getenv("TERRAIN_WEB_APP_NAME") ?: "terrain-converter-web",
            host = System.getenv("TERRAIN_WEB_HOST") ?: "0.0.0.0",
            port = (System.getenv("TERRAIN_WEB_PORT") ?: "8080").toInt(),
            storageRoot = Path.of(System.getenv("TERRAIN_WEB_STORAGE_ROOT") ?: Path.of("web", "data").toString()),
            frontendDist = Path.of(System.getenv("TERRAIN_WEB_FRONTEND_DIST") ?: Path.of("web", "frontend", "dist").toString()),
        )
    }
}

data class ConversionRequest(
    val settings: Settings,
    val jobId: String,
    val options: JobOptions,
    val paths: JobPaths,
    val baseUrl: String,
    val log: (String) -> Unit,
    val verboseLogging: Boolean = true,
)

data class ConversionOutcome(
    val bounds: BBox,
    val tileCount: Int,
)

data class AppDependencies(
    val settings: Settings = Settings.fromEnv(),
    val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    },
    val now: () -> String = { Instant.now().toString() },
    val jobIdProvider: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    val tilesetIdProvider: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    val conversionRunner: suspend (ConversionRequest) -> ConversionOutcome = ::runConversionJob,
    val launcher: (CoroutineScope, suspend () -> Unit) -> Unit = { scope, block -> scope.launch { block() } },
)

private class WebSocketManager(private val json: Json) {
    private val connections = ConcurrentHashMap<String, MutableSet<io.ktor.websocket.WebSocketSession>>()

    fun connect(jobId: String, session: io.ktor.websocket.WebSocketSession) {
        connections.computeIfAbsent(jobId) { Collections.synchronizedSet(linkedSetOf()) }.add(session)
    }

    fun disconnect(jobId: String, session: io.ktor.websocket.WebSocketSession) {
        connections[jobId]?.let {
            it.remove(session)
            if (it.isEmpty()) connections.remove(jobId)
        }
    }

    suspend fun broadcast(jobId: String, payload: String) {
        val sockets = connections[jobId]?.toList() ?: return
        sockets.forEach { socket ->
            try {
                socket.send(Frame.Text(payload))
            } catch (_: Exception) {
                disconnect(jobId, socket)
            }
        }
    }

    suspend fun broadcastJob(job: JobDetail) {
        broadcast(job.id, json.encodeToString(JobEvent(job = job)))
    }

    suspend fun broadcastLog(jobId: String, line: String) {
        broadcast(jobId, json.encodeToString(LogEvent(line = line)))
    }
}

private class JobManager(
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

private data class AppState(
    val dependencies: AppDependencies,
    val storage: Storage,
    val websocketManager: WebSocketManager,
    val jobs: JobManager,
    val tilesets: MutableMap<String, MBTilesTileset>,
)

fun main() {
    val dependencies = AppDependencies()
    embeddedServer(Netty, host = dependencies.settings.host, port = dependencies.settings.port) {
        terrainWebModule(dependencies)
    }.start(wait = true)
}

fun Application.terrainWebModule(dependencies: AppDependencies = AppDependencies()) {
    val storage = Storage(dependencies.settings.storageRoot)
    val websocketManager = WebSocketManager(dependencies.json)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = AppState(
        dependencies = dependencies,
        storage = storage,
        websocketManager = websocketManager,
        jobs = JobManager(dependencies, storage, websocketManager, scope),
        tilesets = ConcurrentHashMap(),
    )

    install(ContentNegotiation) { json(dependencies.json) }
    install(WebSockets)
    install(CORS) {
        anyHost()
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
    }

    routing {
        get("/api/health") {
            call.respond(HealthPayload())
        }

        get("/api/server-info") {
            call.respond(serverInfo(call.request.host(), requestBaseUrl(call), requestScheme(call), requestPort(call)))
        }

        get("/api/jobs") {
            call.respond(state.jobs.listJobs())
        }

        get("/api/jobs/{jobId}") {
            val jobId = call.parameters["jobId"]!!
            val job = runCatching { state.jobs.getJob(jobId) }.getOrElse {
                return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("Job not found"))
            }
            call.respond(job)
        }

        get("/api/jobs/{jobId}/logs") {
            val jobId = call.parameters["jobId"]!!
            val job = runCatching { state.jobs.getJob(jobId) }.getOrElse {
                return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("Job not found"))
            }
            call.respond(LogPayload(job.logs))
        }

        post("/api/jobs") {
            val tempRoot = Files.createTempDirectory(state.storage.root, "job-upload-")
            try {
                val parsed = parseJobMultipart(call.receiveMultipart(), tempRoot)
                if (parsed.hgtFiles.isEmpty()) {
                    return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("hgt_files is required"))
                }
                if (parsed.bboxMode !in setOf("auto", "manual")) {
                    return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("bbox_mode must be auto or manual"))
                }
                val bbox = if (parsed.bboxMode == "manual") {
                    val west = parsed.form["west"]?.toDoubleOrNull()
                    val south = parsed.form["south"]?.toDoubleOrNull()
                    val east = parsed.form["east"]?.toDoubleOrNull()
                    val north = parsed.form["north"]?.toDoubleOrNull()
                    if (west == null || south == null || east == null || north == null) {
                        return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Manual bbox requires west/south/east/north"))
                    }
                    BBox(west, south, east, north)
                } else {
                    null
                }
                val minzoom = if (parsed.form.containsKey("minzoom")) {
                    parsed.form["minzoom"]!!.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("minzoom must be an integer"))
                } else {
                    8
                }
                val maxzoom = if (parsed.form.containsKey("maxzoom")) {
                    parsed.form["maxzoom"]!!.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("maxzoom must be an integer"))
                } else {
                    12
                }
                val tileSize = if (parsed.form.containsKey("tile_size")) {
                    parsed.form["tile_size"]!!.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("tile_size must be an integer"))
                } else {
                    256
                }
                val options = JobOptions(
                    bboxMode = parsed.bboxMode,
                    bbox = bbox,
                    minzoom = minzoom,
                    maxzoom = maxzoom,
                    tileSize = tileSize,
                    scheme = parsed.form["scheme"] ?: "xyz",
                    encoding = parsed.form["encoding"] ?: "mapbox",
                )
                val job = state.jobs.createJob(options, parsed.baseMbtiles != null)
                val paths = state.storage.pathsFor(job.id)
                parsed.hgtFiles.forEach { upload -> state.storage.moveFile(upload.path, paths.uploads.resolve(upload.name)) }
                parsed.baseMbtiles?.let { state.storage.moveFile(it.path, paths.baseMbtiles) }
                val baseUrl = publicBaseUrl(requestScheme(call), call.request.host(), requestPort(call))
                state.jobs.startJob(job.id, baseUrl)
                call.respond(state.jobs.getJob(job.id))
            } finally {
                cleanupTempDir(tempRoot)
            }
        }

        webSocket("/ws/jobs/{jobId}") {
            val jobId = call.parameters["jobId"]!!
            val job = runCatching { state.jobs.getJob(jobId) }.getOrElse {
                close(CloseReason(4404.toShort(), "Job not found"))
                return@webSocket
            }
            state.websocketManager.connect(jobId, this)
            send(Frame.Text(dependencies.json.encodeToString(JobEvent(job = job))))
            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Close) return@consumeEach
                }
            } finally {
                state.websocketManager.disconnect(jobId, this)
            }
        }

        get("/api/jobs/{jobId}/downloads/{artifact}") {
            val jobId = call.parameters["jobId"]!!
            val artifact = call.parameters["artifact"]!!
            val paths = state.storage.pathsFor(jobId)
            val file = when (artifact) {
                "terrain-rgb.mbtiles" -> paths.terrainMbtiles
                "tiles.json" -> paths.tilejson
                "style.json" -> paths.stylejson
                else -> null
            }
            if (file == null || !file.exists()) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("Artifact not found"))
            }
            call.respondFile(file.toFile())
        }

        get("/api/jobs/{jobId}/terrain/{z}/{x}/{y}.png") {
            val jobId = call.parameters["jobId"]!!
            val job = runCatching { state.jobs.getJob(jobId) }.getOrElse {
                return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("Job not found"))
            }
            val z = call.parameters["z"]!!.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Tile coordinates must be integers"))
            val x = call.parameters["x"]!!.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Tile coordinates must be integers"))
            val y = call.parameters["y"]!!.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Tile coordinates must be integers"))
            val xyzY = if (job.options.scheme == "xyz") y else ((1 shl z) - 1) - y
            val tile = state.storage.pathsFor(jobId).terrainRoot.resolve("$z").resolve("$x").resolve("$xyzY.png")
            if (!tile.exists()) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("Tile not found"))
            }
            call.respondBytes(Files.readAllBytes(tile), ContentType.Image.PNG)
        }

        get("/api/jobs/{jobId}/base/{z}/{x}/{y}") {
            val jobId = call.parameters["jobId"]!!
            val z = call.parameters["z"]!!.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Tile coordinates must be integers"))
            val x = call.parameters["x"]!!.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Tile coordinates must be integers"))
            val y = call.parameters["y"]!!.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Tile coordinates must be integers"))
            val tile = MBTilesServer(state.storage.pathsFor(jobId).baseMbtiles).getTile(z, x, y)
            if (tile == null) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("Tile not found"))
            }
            call.respondBytes(tile.first, ContentType.parse(tile.second))
        }

        get("/api/jobs/{jobId}/tilejson") {
            val file = state.storage.pathsFor(call.parameters["jobId"]!!).tilejson
            if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("TileJSON not found"))
            call.respondText(file.readText(), ContentType.Application.Json)
        }

        get("/api/jobs/{jobId}/style") {
            val file = state.storage.pathsFor(call.parameters["jobId"]!!).stylejson
            if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("Style not found"))
            call.respondText(file.readText(), ContentType.Application.Json)
        }

        get("/api/mbtiles") {
            try {
                call.respond(state.tilesets.values.toList())
            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, ErrorPayload("Failed to list tilesets: ${e.message}"))
            }
        }

        post("/api/mbtiles") {
            val tempRoot = Files.createTempDirectory(state.storage.root, "mbtiles-upload-")
            try {
                val parsed = parseMbtilesMultipart(call.receiveMultipart(), tempRoot)
                val sourceType = parsed.form["source_type"] ?: "auto"
                if (parsed.file == null || !parsed.file.name.lowercase().endsWith(".mbtiles")) {
                    return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Upload a .mbtiles file"))
                }
                if (sourceType !in setOf("auto", "raster", "raster-dem")) {
                    return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("source_type must be auto, raster, or raster-dem"))
                }
                val tilesetId = dependencies.tilesetIdProvider()
                val paths = state.storage.tilesetPathsFor(tilesetId)
                state.storage.moveFile(parsed.file.path, paths.mbtiles)
                val server = MBTilesServer(paths.mbtiles)
                val metadata = server.getMetadata()
                val resolvedSourceType = if (sourceType == "auto") guessMbtilesSourceType(parsed.file.name, metadata) else sourceType
                val tileset = buildMbtilesTilesetPayload(
                    scheme = requestScheme(call),
                    requestHost = call.request.host(),
                    requestPort = requestPort(call),
                    tilesetId = tilesetId,
                    filename = parsed.file.name,
                    createdAt = dependencies.now(),
                    server = server,
                    sourceType = resolvedSourceType,
                )
                state.tilesets[tilesetId] = tileset
                call.respond(tileset)
            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, ErrorPayload("Upload failed: ${e.message}"))
            } finally {
                cleanupTempDir(tempRoot)
            }
        }

        get("/api/mbtiles/{tilesetId}") {
            val tileset = state.tilesets[call.parameters["tilesetId"]!!]
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("MBTiles tileset not found"))
            call.respond(tileset)
        }

        get("/api/mbtiles/{tilesetId}/metadata") {
            val tilesetId = call.parameters["tilesetId"]!!
            if (!state.tilesets.containsKey(tilesetId)) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("MBTiles tileset not found"))
            }
            call.respond(MBTilesServer(state.storage.tilesetPathsFor(tilesetId).mbtiles).getMetadata())
        }

        get("/api/mbtiles/{tilesetId}/tilejson") {
            val tileset = state.tilesets[call.parameters["tilesetId"]!!]
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("MBTiles tileset not found"))
            call.respondText(renderPrettyJson(buildMbtilesTilejson(tileset)), ContentType.Application.Json)
        }

        get("/api/mbtiles/{tilesetId}/style") {
            val tileset = state.tilesets[call.parameters["tilesetId"]!!]
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("MBTiles tileset not found"))
            call.respondText(renderPrettyJson(buildMbtilesStyle(tileset)), ContentType.Application.Json)
        }

        get("/api/mbtiles/{tilesetId}/style-mobile") {
            val tileset = state.tilesets[call.parameters["tilesetId"]!!]
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("MBTiles tileset not found"))
            call.respondText(renderPrettyJson(buildMbtilesMobileStyle(tileset)), ContentType.Application.Json)
        }

        get("/api/mbtiles/{tilesetId}/{z}/{x}/{y}.{ext}") {
            val segments = call.request.path().split('/').filter { it.isNotBlank() }
            val yValue = segments.last().substringBeforeLast('.')
            val xValue = segments[segments.lastIndex - 1]
            val zValue = segments[segments.lastIndex - 2]
            serveMbtilesTile(call.parameters["tilesetId"]!!, zValue, xValue, yValue, state, call)
        }

        get("/api/mbtiles/{tilesetId}/{z}/{x}/{y}") {
            val segments = call.request.path().split('/').filter { it.isNotBlank() }
            val yValue = segments.last()
            val xValue = segments[segments.lastIndex - 1]
            val zValue = segments[segments.lastIndex - 2]
            serveMbtilesTile(call.parameters["tilesetId"]!!, zValue, xValue, yValue, state, call)
        }

        if (dependencies.settings.frontendDist.exists()) {
            staticFiles("/", dependencies.settings.frontendDist.toFile()) {
                default("index.html")
            }
        }
    }
}

private suspend fun serveMbtilesTile(
    tilesetId: String,
    zValue: String,
    xValue: String,
    yValue: String,
    state: AppState,
    call: io.ktor.server.application.ApplicationCall,
) {
    if (!state.tilesets.containsKey(tilesetId)) {
        return call.respond(HttpStatusCode.NotFound, ErrorPayload("MBTiles tileset not found"))
    }
    val z = zValue.toIntOrNull()
        ?: return call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Tile coordinates must be integers"))
    val x = xValue.toIntOrNull()
        ?: return call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Tile coordinates must be integers"))
    val y = yValue.toIntOrNull()
        ?: return call.respond(HttpStatusCode.UnprocessableEntity, ErrorPayload("Tile coordinates must be integers"))
    val tile = MBTilesServer(state.storage.tilesetPathsFor(tilesetId).mbtiles).getTile(z, x, y)
    if (tile == null) {
        return call.respond(HttpStatusCode.NotFound, ErrorPayload("Tile not found"))
    }
    call.respondBytes(tile.first, ContentType.parse(tile.second))
}

private data class TempUpload(val name: String, val path: Path)

private data class ParsedJobMultipart(
    val form: Map<String, String>,
    val bboxMode: String,
    val hgtFiles: List<TempUpload>,
    val baseMbtiles: TempUpload?,
)

private data class ParsedMbtilesMultipart(
    val form: Map<String, String>,
    val file: TempUpload?,
)

private suspend fun parseJobMultipart(multipart: io.ktor.http.content.MultiPartData, tempRoot: Path): ParsedJobMultipart {
    val form = linkedMapOf<String, String>()
    val hgtFiles = mutableListOf<TempUpload>()
    var baseMbtiles: TempUpload? = null
    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> form[part.name ?: ""] = part.value
            is PartData.FileItem -> {
                val name = cleanFilename(part.originalFileName ?: "upload.bin")
                val destination = tempRoot.resolve(UUID.randomUUID().toString()).resolve(name)
                destination.parent?.let { Files.createDirectories(it) }
                Files.write(destination, part.provider().readBytes())
                val upload = TempUpload(name, destination)
                when (part.name) {
                    "hgt_files" -> hgtFiles += upload
                    "base_mbtiles" -> baseMbtiles = upload
                }
            }
            else -> {}
        }
        part.dispose()
    }
    return ParsedJobMultipart(form, form["bbox_mode"] ?: "auto", hgtFiles, baseMbtiles)
}

private suspend fun parseMbtilesMultipart(multipart: io.ktor.http.content.MultiPartData, tempRoot: Path): ParsedMbtilesMultipart {
    val form = linkedMapOf<String, String>()
    var file: TempUpload? = null
    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> form[part.name ?: ""] = part.value
            is PartData.FileItem -> if (part.name == "mbtiles") {
                val name = cleanFilename(part.originalFileName ?: "tiles.mbtiles")
                val destination = tempRoot.resolve(UUID.randomUUID().toString()).resolve(name)
                destination.parent?.let { Files.createDirectories(it) }
                Files.write(destination, part.provider().readBytes())
                file = TempUpload(name, destination)
            }
            else -> {}
        }
        part.dispose()
    }
    return ParsedMbtilesMultipart(form, file)
}

private fun cleanFilename(value: String): String = value.replace('\\', '/').substringAfterLast('/')

@OptIn(ExperimentalPathApi::class)
private fun cleanupTempDir(path: Path) {
    runCatching { path.deleteRecursively() }
}

private fun terrainTileUrl(jobId: String): String = "/api/jobs/$jobId/terrain/{z}/{x}/{y}.png"

private fun baseTileUrl(jobId: String): String = "/api/jobs/$jobId/base/{z}/{x}/{y}"

internal fun writeJobDocuments(jobId: String, options: JobOptions, bounds: BBox, tilejsonPath: Path, stylejsonPath: Path, hasBaseMbtiles: Boolean) {
    val terrainUrl = terrainTileUrl(jobId)
    val tilejson = buildTileJson(
        bounds = Bounds(bounds.west, bounds.south, bounds.east, bounds.north),
        minZoom = options.minzoom,
        maxZoom = options.maxzoom,
        tilesUrl = terrainUrl,
        name = "terrain-dem",
        scheme = options.scheme,
        encoding = options.encoding,
        tileSize = options.tileSize,
    )
    @Suppress("UNCHECKED_CAST")
    val style = buildStyle(
        tilesUrl = terrainUrl,
        sourceName = "terrain-dem",
        scheme = options.scheme,
        encoding = options.encoding,
        tileSize = options.tileSize,
    ) as LinkedHashMap<String, Any?>
    style["glyphs"] = "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf"
    style["center"] = tilejson["center"]
    style["zoom"] = options.minzoom
    val layers = (style["layers"] as List<Any?>).toMutableList()
    layers += linkedMapOf(
        "id" to "background",
        "type" to "background",
        "paint" to linkedMapOf("background-color" to "#0f172a"),
    )
    @Suppress("UNCHECKED_CAST")
    val sources = style["sources"] as LinkedHashMap<String, Any?>
    if (hasBaseMbtiles) {
        sources["base-map"] = linkedMapOf(
            "type" to "raster",
            "tiles" to listOf(baseTileUrl(jobId)),
            "tileSize" to 256,
        )
        layers += linkedMapOf(
            "id" to "base-map",
            "type" to "raster",
            "source" to "base-map",
        )
    }
    layers += linkedMapOf(
        "id" to "terrain-hillshade",
        "type" to "hillshade",
        "source" to "terrain-dem",
    )
    style["layers"] = layers
    tilejsonPath.writeText(renderPrettyJson(tilejson))
    stylejsonPath.writeText(renderPrettyJson(style))
}

private suspend fun runConversionJob(request: ConversionRequest): ConversionOutcome {
    val overallStartTime = System.currentTimeMillis()

    val inputPaths = prepareHgtInputs(request.paths, request.verboseLogging, request.log)

    if (request.verboseLogging) {
        request.log("=".repeat(50))
        request.log("Starting terrain conversion job: ${request.jobId}")
        request.log("=".repeat(50))
        request.log("[INPUT] Prepared ${inputPaths.size} HGT input(s)")
        inputPaths.forEachIndexed { index, path ->
            request.log("[INPUT]   ${index + 1}. ${path.fileName}")
        }

        request.log("[PARAMS] Conversion settings:")
        request.log("[PARAMS]   Min Zoom: ${request.options.minzoom}")
        request.log("[PARAMS]   Max Zoom: ${request.options.maxzoom}")
        request.log("[PARAMS]   Tile Size: ${request.options.tileSize}px")
        request.log("[PARAMS]   Scheme: ${request.options.scheme}")
        request.log("[PARAMS]   Encoding: ${request.options.encoding}")
        request.log("[PARAMS]   Workers: ${Runtime.getRuntime().availableProcessors() - 1}")

        if (request.options.bbox != null) {
            request.log("[PARAMS]   BBOX (manual): west=${request.options.bbox.west}, south=${request.options.bbox.south}, east=${request.options.bbox.east}, north=${request.options.bbox.north}")
        } else {
            request.log("[PARAMS]   BBOX: auto-detected from input data")
        }

        if (request.paths.baseMbtiles.exists()) {
            request.log("[PARAMS]   Base MBTiles: ${request.paths.baseMbtiles.fileName}")
        }

        request.log("[OUTPUT] Output paths:")
        request.log("[OUTPUT]   Tiles: ${request.paths.terrainRoot}")
        request.log("[OUTPUT]   MBTiles: ${request.paths.terrainMbtiles}")
        request.log("[OUTPUT]   TileJSON: ${request.paths.tilejson}")
        request.log("[OUTPUT]   Style: ${request.paths.stylejson}")
        request.log("[CONVERT] Starting conversion process...")
    }

    val conversionStart = System.currentTimeMillis()
    val result = runTerrainConversion(
        ConversionOptions(
            inputs = inputPaths,
            outputMbtiles = request.paths.terrainMbtiles,
            tileRoot = request.paths.terrainRoot,
            tileJson = request.paths.tilejson,
            styleJson = request.paths.stylejson,
            tilesUrl = "${request.baseUrl}/api/jobs/${request.jobId}/terrain/{z}/{x}/{y}.png",
            minZoom = request.options.minzoom,
            maxZoom = request.options.maxzoom,
            bbox = request.options.bbox?.let { Bounds(it.west, it.south, it.east, it.north) },
            tileSize = request.options.tileSize,
            scheme = request.options.scheme,
            encoding = request.options.encoding,
        )
    )
    val conversionTime = System.currentTimeMillis() - conversionStart

    if (request.verboseLogging) {
        request.log("[CONVERT] Conversion completed in ${conversionTime}ms")
    }

    val tilejsonText = request.paths.tilejson.readText()
    val bounds = parseBoundsFromTilejson(tilejsonText)
    writeJobDocuments(
        jobId = request.jobId,
        options = request.options,
        bounds = bounds,
        tilejsonPath = request.paths.tilejson,
        stylejsonPath = request.paths.stylejson,
        hasBaseMbtiles = request.paths.baseMbtiles.exists(),
    )

    val actualTileCount = countRenderedTiles(request.paths.terrainRoot)
    val totalTime = System.currentTimeMillis() - overallStartTime

    if (request.verboseLogging) {
        request.log("[DOCS] Generated TileJSON and Style JSON")
        request.log("=".repeat(50))
        request.log("[SUMMARY] Conversion completed successfully")
        request.log("[SUMMARY]   Tiles generated: $actualTileCount")
        request.log("[SUMMARY]   Bounds: west=${bounds.west}, south=${bounds.south}, east=${bounds.east}, north=${bounds.north}")
        request.log("[SUMMARY]   Total time: ${totalTime}ms")
        if (actualTileCount > 0) {
            request.log("[SUMMARY]   Average: ${totalTime / actualTileCount}ms per tile")
        }
        request.log("=".repeat(50))
    }

    return ConversionOutcome(bounds = bounds, tileCount = actualTileCount)
}

private fun bboxArgs(options: JobOptions): List<String> {
    val bbox = options.bbox ?: return emptyList()
    if (options.bboxMode != "manual") return emptyList()
    return listOf("--bbox", bbox.west.toString(), bbox.south.toString(), bbox.east.toString(), bbox.north.toString())
}

private fun prepareHgtInputs(paths: JobPaths, verboseLogging: Boolean = true, log: ((String) -> Unit)? = null): List<Path> {
    val inputs = mutableListOf<Path>()

    Files.list(paths.uploads).use { uploads ->
        uploads.sorted().forEach { upload ->
            if (upload.toFile().name == "base.mbtiles") {
                if (verboseLogging) log?.invoke("[INPUT] Skipping base.mbtiles (used as base map)")
                return@forEach
            }
            val extracted = extractInput(upload, paths.inputs, verboseLogging, log)
            inputs.addAll(extracted)
        }
    }

    require(inputs.isNotEmpty()) { "No HGT files were provided. Please upload at least one .hgt file or a .zip containing .hgt files." }
    return inputs
}

private fun extractInput(source: Path, targetDir: Path, verboseLogging: Boolean = true, log: ((String) -> Unit)? = null): List<Path> {
    return when (source.extension.lowercase()) {
        "zip" -> {
            if (verboseLogging) log?.invoke("[ZIP] Extracting archive: ${source.fileName}")
            extractZipInputs(source, targetDir, verboseLogging, log)
        }
        "hgt" -> {
            if (verboseLogging) log?.invoke("[HGT] Using file: ${source.fileName}")
            listOf(source)
        }
        else -> {
            if (verboseLogging) log?.invoke("[SKIP] Unsupported file type: ${source.fileName}")
            emptyList()
        }
    }
}

private fun extractZipInputs(source: Path, targetDir: Path, verboseLogging: Boolean = true, log: ((String) -> Unit)? = null): List<Path> {
    val extracted = mutableListOf<Path>()
    var entryCount = 0
    ZipInputStream(Files.newInputStream(source)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entryCount++
            if (entry.isDirectory) continue
            if (!entry.name.lowercase().endsWith(".hgt")) {
                if (verboseLogging) log?.invoke("[ZIP]   Skipping non-HGT file: ${entry.name}")
                continue
            }
            val destination = targetDir.resolve(cleanFilename(entry.name))
            destination.parent?.let { Files.createDirectories(it) }
            Files.newOutputStream(destination).use { output -> zip.copyTo(output) }
            extracted.add(destination)
        }
    }
    if (verboseLogging) log?.invoke("[ZIP]   Scanned $entryCount entries, extracted ${extracted.size} HGT file(s)")
    return extracted
}

private fun parseBoundsFromTilejson(jsonText: String): BBox {
    val match = Regex("\"bounds\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(jsonText)
        ?: throw IllegalStateException("Missing bounds in tiles.json")
    val values = match.groupValues[1].split(',').map { it.trim().toDouble() }
    return BBox(values[0], values[1], values[2], values[3])
}

private fun countRenderedTiles(terrainRoot: Path): Int {
    Files.walk(terrainRoot).use { paths ->
        return paths.filter { it.isRegularFile() && it.name.endsWith(".png") }.count().toInt()
    }
}

private fun isUsableHost(host: String?): Boolean = !host.isNullOrBlank() && host !in setOf(".", "0.0.0.0", "localhost", "::", "::1")

private fun isPrivateIpv4(address: String): Boolean {
    val octets = address.split('.')
    if (octets.size != 4) return false
    val first = octets[0].toIntOrNull() ?: return false
    val second = octets[1].toIntOrNull() ?: return false
    return first == 10 || (first == 192 && second == 168) || (first == 172 && second in 16..31)
}

private fun resolvePublicHost(requestHost: String): String {
    val configured = System.getenv("TERRAIN_WEB_PUBLIC_HOST")?.trim().orEmpty()
    if (isUsableHost(configured)) return configured
    if (requestHost !in setOf("127.0.0.1", "localhost", "::1") && isUsableHost(requestHost)) return requestHost
    runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress("8.8.8.8", 80))
            val address = socket.localAddress.hostAddress
            if (!address.startsWith("127.") && isUsableHost(address)) return address
        }
    }
    (NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) } ?: emptyList()).forEach { iface ->
        if (!iface.isUp || iface.isLoopback || iface.isVirtual) return@forEach
        Collections.list(iface.inetAddresses).filterIsInstance<Inet4Address>().map { it.hostAddress }.forEach { address ->
            if (!address.startsWith("127.") && isPrivateIpv4(address) && isUsableHost(address)) return address
        }
    }
    runCatching {
        InetAddress.getLocalHost().hostAddress?.let { if (!it.startsWith("127.") && isUsableHost(it)) return it }
    }
    return requestHost
}

private fun requestScheme(call: io.ktor.server.application.ApplicationCall): String = call.request.origin.scheme

private fun requestPort(call: io.ktor.server.application.ApplicationCall): Int = call.request.origin.serverPort

private fun baseUrl(scheme: String, host: String, port: Int): String {
    val defaultPort = if (scheme == "https") 443 else 80
    val portSuffix = if (port == defaultPort) "" else ":$port"
    return "$scheme://$host$portSuffix"
}

private fun publicBaseUrl(scheme: String, requestHost: String, port: Int): String = baseUrl(scheme, resolvePublicHost(requestHost), port)

private fun requestBaseUrl(call: io.ktor.server.application.ApplicationCall): String = baseUrl(requestScheme(call), call.request.host(), requestPort(call))

private fun serverInfo(requestHost: String, requestBaseUrl: String, scheme: String, port: Int): ServerInfo {
    val publicHost = resolvePublicHost(requestHost)
    val addresses = mutableListOf(
        ServerAddress(
            id = "mobile",
            label = "Mobile / Wi-Fi",
            host = publicHost,
            baseUrl = baseUrl(scheme, publicHost, port),
            description = "Use this address from a phone or another device in the same local network.",
        ),
        ServerAddress(
            id = "localhost",
            label = "This computer",
            host = "127.0.0.1",
            baseUrl = baseUrl(scheme, "127.0.0.1", port),
            description = "Use this address only on the same computer where the server is running.",
        ),
    )
    if (requestHost !in setOf(publicHost, "127.0.0.1", "localhost", "::1")) {
        addresses += ServerAddress(
            id = "request-host",
            label = "Current browser host",
            host = requestHost,
            baseUrl = requestBaseUrl,
            description = "This is the host from which the current browser opened the UI.",
        )
    }
    return ServerInfo(addresses)
}

private fun guessMbtilesSourceType(filename: String, metadata: Map<String, String>): String {
    val hints = listOf(filename, metadata["name"], metadata["description"]).joinToString(" ").lowercase()
    return if ("terrain-rgb" in hints || "terrain" in hints || "dem" in hints) "raster-dem" else "raster"
}

private fun buildMbtilesTilesetPayload(
    scheme: String,
    requestHost: String,
    requestPort: Int,
    tilesetId: String,
    filename: String,
    createdAt: String,
    server: MBTilesServer,
    sourceType: String,
): MBTilesTileset {
    val publicBaseUrl = publicBaseUrl(scheme, requestHost, requestPort)
    val tileFormat = server.getFormat()
    val tilePath = "/api/mbtiles/$tilesetId/{z}/{x}/{y}.$tileFormat"
    val tilejsonPath = "/api/mbtiles/$tilesetId/tilejson"
    val stylePath = "/api/mbtiles/$tilesetId/style"
    val mobileStylePath = "/api/mbtiles/$tilesetId/style-mobile"
    return MBTilesTileset(
        id = tilesetId,
        filename = filename,
        createdAt = createdAt,
        tileUrlTemplate = tilePath,
        publicTileUrlTemplate = "$publicBaseUrl$tilePath",
        tilejsonUrl = tilejsonPath,
        publicTilejsonUrl = "$publicBaseUrl$tilejsonPath",
        styleUrl = stylePath,
        publicStyleUrl = "$publicBaseUrl$stylePath",
        mobileStyleUrl = mobileStylePath,
        publicMobileStyleUrl = "$publicBaseUrl$mobileStylePath",
        name = server.getName(),
        attribution = server.getAttribution(),
        sourceType = sourceType,
        tileFormat = tileFormat,
        minzoom = server.getMinzoom(),
        maxzoom = server.getMaxzoom(),
        bounds = server.getBounds(),
        view = server.getView(),
    )
}

private fun buildMbtilesTilejson(tileset: MBTilesTileset): Map<String, Any?> {
    val bounds = tileset.bounds ?: BBox(-180.0, -85.05112878, 180.0, 85.05112878)
    val minzoom = tileset.minzoom ?: 0
    val maxzoom = tileset.maxzoom ?: 14
    if (tileset.sourceType == "raster-dem") {
        return buildTileJson(
            bounds = Bounds(bounds.west, bounds.south, bounds.east, bounds.north),
            minZoom = minzoom,
            maxZoom = maxzoom,
            tilesUrl = tileset.publicTileUrlTemplate,
            name = tileset.name ?: tileset.filename,
            scheme = "xyz",
            encoding = "mapbox",
            tileSize = 256,
        )
    }
    val centerLon = (bounds.west + bounds.east) / 2.0
    val centerLat = (bounds.south + bounds.north) / 2.0
    return linkedMapOf(
        "tilejson" to "3.0.0",
        "name" to (tileset.name ?: tileset.filename),
        "type" to "raster",
        "scheme" to "xyz",
        "format" to tileset.tileFormat,
        "tiles" to listOf(tileset.publicTileUrlTemplate),
        "bounds" to listOf(bounds.west, bounds.south, bounds.east, bounds.north),
        "center" to listOf(centerLon, centerLat, minzoom),
        "minzoom" to minzoom,
        "maxzoom" to maxzoom,
        "tileSize" to 256,
    ).apply {
        if (tileset.attribution != null) put("attribution", tileset.attribution)
    }
}

private fun buildMbtilesStyle(tileset: MBTilesTileset): Map<String, Any?> {
    val sourceName = "tileset"
    if (tileset.sourceType == "raster-dem") {
        @Suppress("UNCHECKED_CAST")
        val style = buildStyle(
            tilesUrl = tileset.publicTileUrlTemplate,
            sourceName = sourceName,
            styleName = tileset.name ?: tileset.filename,
            scheme = "xyz",
            encoding = "mapbox",
            tileSize = 256,
        ) as LinkedHashMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val sources = style["sources"] as LinkedHashMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val source = sources[sourceName] as LinkedHashMap<String, Any?>
        tileset.bounds?.let { source["bounds"] = listOf(it.west, it.south, it.east, it.north) }
        tileset.minzoom?.let { source["minzoom"] = it }
        tileset.maxzoom?.let { source["maxzoom"] = it }
        tileset.attribution?.let { source["attribution"] = it }
        style["glyphs"] = "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf"
        sources["osm-base"] = linkedMapOf(
            "type" to "raster",
            "tiles" to listOf(
                "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
            ),
            "tileSize" to 256,
            "minzoom" to 0,
            "maxzoom" to 19,
            "attribution" to "OpenStreetMap contributors",
        )
        tileset.view?.let {
            style["center"] = listOf(it.centerLon, it.centerLat)
            style["zoom"] = it.zoom
        }
        val layers = (style["layers"] as List<Any?>).toMutableList()
        layers += linkedMapOf("id" to "osm-base-layer", "type" to "raster", "source" to "osm-base")
        layers += linkedMapOf(
            "id" to "terrain-hillshade",
            "type" to "hillshade",
            "source" to sourceName,
            "paint" to linkedMapOf(
                "hillshade-exaggeration" to 1.0,
                "hillshade-shadow-color" to "rgba(0, 0, 0, 0.35)",
                "hillshade-highlight-color" to "rgba(255, 255, 255, 0.25)",
                "hillshade-accent-color" to "rgba(0, 0, 0, 0.12)",
            ),
        )
        style["layers"] = layers
        style["terrain"] = linkedMapOf("source" to sourceName, "exaggeration" to 1.0)
        return style
    }
    val source = linkedMapOf<String, Any?>(
        "type" to "raster",
        "tiles" to listOf(tileset.publicTileUrlTemplate),
        "tileSize" to 256,
    )
    tileset.bounds?.let { source["bounds"] = listOf(it.west, it.south, it.east, it.north) }
    tileset.minzoom?.let { source["minzoom"] = it }
    tileset.maxzoom?.let { source["maxzoom"] = it }
    tileset.attribution?.let { source["attribution"] = it }
    return linkedMapOf<String, Any?>(
        "version" to 8,
        "name" to (tileset.name ?: tileset.filename),
        "sources" to linkedMapOf(sourceName to source),
        "layers" to listOf(linkedMapOf("id" to "tileset-raster", "type" to "raster", "source" to sourceName)),
    ).apply {
        tileset.view?.let {
            put("center", listOf(it.centerLon, it.centerLat))
            put("zoom", it.zoom)
        }
    }
}

private fun buildMbtilesMobileStyle(tileset: MBTilesTileset): Map<String, Any?> {
    val sourceName = "tileset"
    val source = linkedMapOf<String, Any?>(
        "type" to tileset.sourceType,
        "tiles" to listOf(tileset.publicTileUrlTemplate),
        "tileSize" to 256,
    )
    tileset.bounds?.let { source["bounds"] = listOf(it.west, it.south, it.east, it.north) }
    tileset.minzoom?.let { source["minzoom"] = it }
    tileset.maxzoom?.let { source["maxzoom"] = it }
    if (tileset.sourceType == "raster-dem") source["encoding"] = "mapbox"
    val layers = mutableListOf<Any?>(
        linkedMapOf("id" to "osm-base-layer", "type" to "raster", "source" to "osm-base")
    )
    if (tileset.sourceType == "raster-dem") {
        layers += linkedMapOf(
            "id" to "terrain-hillshade",
            "type" to "hillshade",
            "source" to sourceName,
            "paint" to linkedMapOf(
                "hillshade-exaggeration" to 1.0,
                "hillshade-shadow-color" to "rgba(0, 0, 0, 0.35)",
                "hillshade-highlight-color" to "rgba(255, 255, 255, 0.25)",
                "hillshade-accent-color" to "rgba(0, 0, 0, 0.12)",
            ),
        )
    } else {
        layers += linkedMapOf("id" to "tileset-raster", "type" to "raster", "source" to sourceName)
    }
    return linkedMapOf<String, Any?>(
        "version" to 8,
        "name" to "${tileset.name ?: tileset.filename} (mobile)",
        "sources" to linkedMapOf(
            "osm-base" to linkedMapOf(
                "type" to "raster",
                "tiles" to listOf(
                    "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
                    "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
                    "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
                ),
                "tileSize" to 256,
                "minzoom" to 0,
                "maxzoom" to 19,
                "attribution" to "OpenStreetMap contributors",
            ),
            sourceName to source,
        ),
        "layers" to layers,
    ).apply {
        tileset.view?.let {
            put("center", listOf(it.centerLon, it.centerLat))
            put("zoom", it.zoom)
        }
    }
}
