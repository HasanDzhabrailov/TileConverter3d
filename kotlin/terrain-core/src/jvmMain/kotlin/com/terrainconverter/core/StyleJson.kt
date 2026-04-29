package com.terrainconverter.core

import java.nio.file.Files
import java.nio.file.Path

fun buildStyle(
    tilesUrl: String,
    sourceName: String = "terrain-dem",
    styleName: String = "Terrain DEM Style",
    scheme: String = "xyz",
    encoding: String = "mapbox",
    tileSize: Int = 256,
): Map<String, Any?> = jsonObject(
    "version" to 8,
    "name" to styleName,
    "sources" to jsonObject(
        sourceName to jsonObject(
            "type" to "raster-dem",
            "tiles" to listOf(tilesUrl),
            "encoding" to encoding,
            "tileSize" to tileSize,
            "scheme" to scheme,
        ),
    ),
    "terrain" to jsonObject(
        "source" to sourceName,
        "exaggeration" to 1.0,
    ),
    "layers" to emptyList<Any>(),
)

fun writeStyle(path: Path, style: Map<String, Any?>) {
    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(path, renderPrettyJson(style))
}
