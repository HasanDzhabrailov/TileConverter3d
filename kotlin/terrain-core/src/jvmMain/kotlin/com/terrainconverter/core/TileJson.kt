package com.terrainconverter.core

import java.nio.file.Files
import java.nio.file.Path

fun buildTileJson(
    bounds: Bounds,
    minZoom: Int,
    maxZoom: Int,
    tilesUrl: String,
    name: String = "terrain-dem",
    scheme: String = "xyz",
    encoding: String = "mapbox",
    tileSize: Int = 256,
): Map<String, Any?> {
    val (centerLon, centerLat) = bounds.center()
    return jsonObject(
        "tilejson" to "3.0.0",
        "name" to name,
        "type" to "raster-dem",
        "scheme" to scheme,
        "encoding" to encoding,
        "format" to "png",
        "tileSize" to tileSize,
        "tiles" to listOf(tilesUrl),
        "bounds" to listOf(bounds.west, bounds.south, bounds.east, bounds.north),
        "center" to listOf(centerLon, centerLat, minZoom),
        "minzoom" to minZoom,
        "maxzoom" to maxZoom,
    )
}

fun writeTileJson(path: Path, tileJson: Map<String, Any?>) {
    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(path, renderPrettyJson(tileJson))
}
