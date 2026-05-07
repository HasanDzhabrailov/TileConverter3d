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

fun MBTilesTileset.withPublicBaseUrl(publicBaseUrl: String): MBTilesTileset = copy(
    publicTileUrlTemplate = "$publicBaseUrl$tileUrlTemplate",
    publicTilejsonUrl = tilejsonUrl?.let { "$publicBaseUrl$it" },
    publicStyleUrl = styleUrl?.let { "$publicBaseUrl$it" },
    publicMobileStyleUrl = mobileStyleUrl?.let { "$publicBaseUrl$it" },
)

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
