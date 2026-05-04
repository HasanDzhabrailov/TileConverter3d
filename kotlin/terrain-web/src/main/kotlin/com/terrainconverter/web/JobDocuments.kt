package com.terrainconverter.web

import com.terrainconverter.core.Bounds
import com.terrainconverter.core.buildStyle
import com.terrainconverter.core.buildTileJson
import com.terrainconverter.core.renderPrettyJson
import java.nio.file.Path
import kotlin.io.path.writeText

fun terrainTileUrl(jobId: String): String = "/api/jobs/$jobId/terrain/{z}/{x}/{y}.png"

fun baseTileUrl(jobId: String): String = "/api/jobs/$jobId/base/{z}/{x}/{y}"

fun writeJobDocuments(
    jobId: String,
    options: JobOptions,
    bounds: BBox,
    tilejsonPath: Path,
    stylejsonPath: Path,
    hasBaseMbtiles: Boolean,
) {
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
