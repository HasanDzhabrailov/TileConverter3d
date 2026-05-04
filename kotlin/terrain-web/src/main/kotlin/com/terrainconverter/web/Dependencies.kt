package com.terrainconverter.web

import kotlinx.coroutines.CoroutineScope
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

data class AppState(
    val dependencies: AppDependencies,
    val storage: Storage,
    val websocketManager: WebSocketManager,
    val jobs: JobManager,
    val tilesets: MutableMap<String, MBTilesTileset>,
)
