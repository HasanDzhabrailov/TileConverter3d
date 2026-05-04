package com.terrainconverter.web

import com.terrainconverter.core.renderPrettyJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText

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
        // Health and Info
        get("/api/health") {
            call.respond(HealthPayload())
        }

        get("/api/server-info") {
            call.respond(buildServerInfo(call.request.host(), requestBaseUrl(call), requestScheme(call), requestPort(call)))
        }

        // Job API
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

        // MBTiles API
        get("/api/mbtiles") {
            try {
                val baseUrl = publicBaseUrl(requestScheme(call), call.request.host(), requestPort(call))
                call.respond(state.tilesets.values.map { it.withPublicBaseUrl(baseUrl) })
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
            val baseUrl = publicBaseUrl(requestScheme(call), call.request.host(), requestPort(call))
            call.respond(tileset.withPublicBaseUrl(baseUrl))
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
            val baseUrl = publicBaseUrl(requestScheme(call), call.request.host(), requestPort(call))
            call.respondText(renderPrettyJson(buildMbtilesTilejson(tileset.withPublicBaseUrl(baseUrl))), ContentType.Application.Json)
        }

        get("/api/mbtiles/{tilesetId}/style") {
            val tileset = state.tilesets[call.parameters["tilesetId"]!!]
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("MBTiles tileset not found"))
            val baseUrl = publicBaseUrl(requestScheme(call), call.request.host(), requestPort(call))
            call.respondText(renderPrettyJson(buildMbtilesStyle(tileset.withPublicBaseUrl(baseUrl))), ContentType.Application.Json)
        }

        get("/api/mbtiles/{tilesetId}/style-mobile") {
            val tileset = state.tilesets[call.parameters["tilesetId"]!!]
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorPayload("MBTiles tileset not found"))
            val baseUrl = publicBaseUrl(requestScheme(call), call.request.host(), requestPort(call))
            call.respondText(renderPrettyJson(buildMbtilesMobileStyle(tileset.withPublicBaseUrl(baseUrl))), ContentType.Application.Json)
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

        // Static files
        if (dependencies.settings.frontendDist.exists()) {
            staticFiles("/", dependencies.settings.frontendDist.toFile()) {
                default("index.html")
            }
        }
    }
}

@OptIn(ExperimentalPathApi::class)
private fun cleanupTempDir(path: java.nio.file.Path) {
    runCatching { path.deleteRecursively() }
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
