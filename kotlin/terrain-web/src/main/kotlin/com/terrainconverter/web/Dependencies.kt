package com.terrainconverter.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Settings(
    val appName: String = "terrain-converter-web",
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val storageRoot: Path = Path.of("data"),
    val frontendDist: Path = Path.of("kotlin", "terrain-web-ui", "build", "frontendDist"),
) {
    companion object {
        fun fromEnv(): Settings = Settings(
            appName = System.getenv("TERRAIN_WEB_APP_NAME") ?: "terrain-converter-web",
            host = System.getenv("TERRAIN_WEB_HOST") ?: "0.0.0.0",
            port = (System.getenv("TERRAIN_WEB_PORT") ?: "8080").toInt(),
            storageRoot = Path.of(System.getenv("TERRAIN_WEB_STORAGE_ROOT") ?: "data"),
            frontendDist = Path.of(System.getenv("TERRAIN_WEB_FRONTEND_DIST") ?: Path.of("kotlin", "terrain-web-ui", "build", "frontendDist").toString()),
        )
    }
}

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
    val launcher: (CoroutineScope, suspend () -> Unit) -> Job = { scope, block -> scope.launch { block() } },
)

data class AppState(
    val dependencies: AppDependencies,
    val storage: Storage,
    val websocketManager: WebSocketManager,
    val jobs: JobManager,
    val tilesets: MutableMap<String, MBTilesTileset>,
    val mbtilesUploadProgress: MutableMap<String, MBTilesUploadProgress>,
    val mbtilesUploadProgressUpdatedAt: MutableMap<String, Long>,
    val baseSources: BaseMapSourceRepository,
)
