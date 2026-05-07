package com.terrainconverter.web

import com.terrainconverter.core.Bounds
import com.terrainconverter.core.buildStyle
import com.terrainconverter.core.buildTileJson

private const val DEFAULT_BASE_SOURCE_ID = "openstreetmap"

class UnknownBaseMapSourceException(sourceId: String) : NoSuchElementException(sourceId)

fun resolveStyleBaseSource(repository: BaseMapSourceRepository, sourceId: String?): BaseMapSource {
    val id = sourceId?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_SOURCE_ID
    return repository.get(id) ?: throw UnknownBaseMapSourceException(id)
}

fun buildDynamicJobStyle(job: JobDetail, baseSource: BaseMapSource, publicBaseUrl: String? = null): Map<String, Any?> {
    val bounds = job.result.bounds ?: BBox(-180.0, -85.05112878, 180.0, 85.05112878)
    val terrainUrl = withOptionalPublicBase(terrainTileUrl(job.id), publicBaseUrl)
    val tilejson = buildTileJson(
        bounds = Bounds(bounds.west, bounds.south, bounds.east, bounds.north),
        minZoom = job.options.minzoom,
        maxZoom = job.options.maxzoom,
        tilesUrl = terrainUrl,
        name = "terrain-dem",
        scheme = job.options.scheme,
        encoding = job.options.encoding,
        tileSize = job.options.tileSize,
    )
    @Suppress("UNCHECKED_CAST")
    val style = buildStyle(
        tilesUrl = terrainUrl,
        sourceName = "terrain-dem",
        scheme = job.options.scheme,
        encoding = job.options.encoding,
        tileSize = job.options.tileSize,
    ) as LinkedHashMap<String, Any?>
    style["glyphs"] = "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf"
    style["center"] = tilejson["center"]
    style["zoom"] = job.options.minzoom
    @Suppress("UNCHECKED_CAST")
    val sources = style["sources"] as LinkedHashMap<String, Any?>
    val layers = mutableListOf<Any?>()
    layers += linkedMapOf(
        "id" to "background",
        "type" to "background",
        "paint" to linkedMapOf("background-color" to "#0f172a"),
    )
    addBaseLayer(sources, layers, baseSource, publicBaseUrl)
    layers += linkedMapOf(
        "id" to "terrain-hillshade",
        "type" to "hillshade",
        "source" to "terrain-dem",
    )
    style["layers"] = layers
    return style
}

fun buildDynamicMbtilesStyle(tileset: MBTilesTileset, baseSource: BaseMapSource, publicBaseUrl: String? = null): Map<String, Any?> {
    val resolvedTileset = publicBaseUrl?.let { tileset.withPublicBaseUrl(it) } ?: tileset
    val sourceName = "tileset"
    val source = linkedMapOf<String, Any?>(
        "type" to resolvedTileset.sourceType,
        "tiles" to listOf(resolvedTileset.publicTileUrlTemplate),
        "tileSize" to 256,
    )
    resolvedTileset.bounds?.let { source["bounds"] = listOf(it.west, it.south, it.east, it.north) }
    resolvedTileset.minzoom?.let { source["minzoom"] = it }
    resolvedTileset.maxzoom?.let { source["maxzoom"] = it }
    resolvedTileset.attribution?.let { source["attribution"] = it }
    if (resolvedTileset.sourceType == "raster-dem") {
        source["scheme"] = "xyz"
        source["encoding"] = "mapbox"
    }

    val sources = linkedMapOf<String, Any?>(sourceName to source)
    val layers = mutableListOf<Any?>()
    addBaseLayer(sources, layers, baseSource, publicBaseUrl)
    if (resolvedTileset.sourceType == "raster-dem") {
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
        "name" to (resolvedTileset.name ?: resolvedTileset.filename),
        "sources" to sources,
        "layers" to layers,
    ).apply {
        if (resolvedTileset.sourceType == "raster-dem") {
            put("terrain", linkedMapOf("source" to sourceName, "exaggeration" to 1.0))
            put("glyphs", "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf")
        }
        resolvedTileset.view?.let {
            put("center", listOf(it.centerLon, it.centerLat))
            put("zoom", it.zoom)
        }
    }
}

private fun addBaseLayer(
    sources: LinkedHashMap<String, Any?>,
    layers: MutableList<Any?>,
    baseSource: BaseMapSource,
    publicBaseUrl: String?,
) {
    if (baseSource.id == "none") return
    sources["base-map"] = linkedMapOf(
        "type" to "raster",
        "tiles" to listOf(withOptionalPublicBase(baseSource.urlTemplate, publicBaseUrl)),
        "tileSize" to 256,
        "minzoom" to 0,
        "maxzoom" to baseSource.maxZoom,
    ).apply {
        baseSource.attribution?.let { put("attribution", it) }
    }
    layers += linkedMapOf(
        "id" to "base-map",
        "type" to "raster",
        "source" to "base-map",
    )
}

private fun withOptionalPublicBase(url: String, publicBaseUrl: String?): String =
    if (publicBaseUrl != null && url.startsWith("/")) "$publicBaseUrl$url" else url
