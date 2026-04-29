package com.terrainconverter.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class JobStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("running")
    RUNNING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class BBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

@Serializable
data class MapView(
    @SerialName("center_lon")
    val centerLon: Double,
    @SerialName("center_lat")
    val centerLat: Double,
    val zoom: Int,
)

@Serializable
data class JobOptions(
    @SerialName("bbox_mode")
    val bboxMode: String = "auto",
    val bbox: BBox? = null,
    val minzoom: Int = 8,
    val maxzoom: Int = 12,
    @SerialName("tile_size")
    val tileSize: Int = 256,
    val scheme: String = "xyz",
    val encoding: String = "mapbox",
)

@Serializable
data class JobArtifacts(
    @SerialName("terrain_mbtiles")
    val terrainMbtiles: String? = null,
    val tilejson: String? = null,
    val stylejson: String? = null,
    @SerialName("terrain_tile_url_template")
    val terrainTileUrlTemplate: String? = null,
    @SerialName("public_terrain_tile_url_template")
    val publicTerrainTileUrlTemplate: String? = null,
    @SerialName("public_tilejson")
    val publicTilejson: String? = null,
    @SerialName("public_stylejson")
    val publicStylejson: String? = null,
)

@Serializable
data class JobResult(
    val bounds: BBox? = null,
    @SerialName("tile_count")
    val tileCount: Int? = null,
)

@Serializable
data class JobSummary(
    val id: String,
    val status: JobStatus,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    val options: JobOptions,
    @SerialName("has_base_mbtiles")
    val hasBaseMbtiles: Boolean = false,
    val artifacts: JobArtifacts = JobArtifacts(),
    val result: JobResult = JobResult(),
    val error: String? = null,
)

@Serializable
data class JobDetail(
    val id: String,
    val status: JobStatus,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    val options: JobOptions,
    @SerialName("has_base_mbtiles")
    val hasBaseMbtiles: Boolean = false,
    val artifacts: JobArtifacts = JobArtifacts(),
    val result: JobResult = JobResult(),
    val error: String? = null,
    val logs: List<String> = emptyList(),
)

@Serializable
data class MBTilesTileset(
    val id: String,
    val filename: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("tile_url_template")
    val tileUrlTemplate: String,
    @SerialName("public_tile_url_template")
    val publicTileUrlTemplate: String,
    @SerialName("tilejson_url")
    val tilejsonUrl: String? = null,
    @SerialName("public_tilejson_url")
    val publicTilejsonUrl: String? = null,
    @SerialName("style_url")
    val styleUrl: String? = null,
    @SerialName("public_style_url")
    val publicStyleUrl: String? = null,
    @SerialName("mobile_style_url")
    val mobileStyleUrl: String? = null,
    @SerialName("public_mobile_style_url")
    val publicMobileStyleUrl: String? = null,
    val name: String? = null,
    val attribution: String? = null,
    @SerialName("source_type")
    val sourceType: String = "raster",
    @SerialName("tile_format")
    val tileFormat: String = "png",
    val minzoom: Int? = null,
    val maxzoom: Int? = null,
    val bounds: BBox? = null,
    val view: MapView? = null,
)

@Serializable
data class ServerAddress(
    val id: String,
    val label: String,
    val host: String,
    @SerialName("base_url")
    val baseUrl: String,
    val description: String,
)

@Serializable
data class ServerInfo(val addresses: List<ServerAddress> = emptyList())

@Serializable
data class LogPayload(val logs: List<String>)

@Serializable
data class ErrorPayload(val detail: String)

@Serializable
data class HealthPayload(val status: String = "ok")

@Serializable
data class JobEvent(
    val type: String = "job",
    val job: JobDetail,
)

@Serializable
data class LogEvent(
    val type: String = "log",
    val line: String,
)
