package com.terrainconverter.web

import com.terrainconverter.core.Bounds
import com.terrainconverter.core.buildStyle
import com.terrainconverter.core.buildTileJson

fun guessMbtilesSourceType(filename: String, metadata: Map<String, String>): String {
    val hints = listOf(filename, metadata["name"], metadata["description"]).joinToString(" ").lowercase()
    return if ("terrain-rgb" in hints || "terrain" in hints || "dem" in hints) "raster-dem" else "raster"
}

fun buildMbtilesTilesetPayload(
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

fun buildMbtilesTilejson(tileset: MBTilesTileset): Map<String, Any?> {
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

fun buildMbtilesStyle(tileset: MBTilesTileset): Map<String, Any?> {
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

fun buildMbtilesMobileStyle(tileset: MBTilesTileset): Map<String, Any?> {
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
