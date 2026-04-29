package com.terrainconverter.core

import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

val DEFAULT_WORKERS: Int = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)

data class ConversionOptions(
    val inputs: List<Path>,
    val outputMbtiles: Path,
    val tileRoot: Path,
    val tileJson: Path,
    val styleJson: Path,
    val tilesUrl: String,
    val minZoom: Int = 8,
    val maxZoom: Int = 12,
    val bbox: Bounds? = null,
    val tileSize: Int = 256,
    val scheme: String = "xyz",
    val encoding: String = "mapbox",
    val name: String = "terrain-dem",
    val workers: Int = DEFAULT_WORKERS,
)

data class ConversionResult(
    val bounds: Bounds,
    val tileCount: Int,
    val outputMbtiles: Path,
    val tileJson: Path,
    val styleJson: Path,
    val scheme: String,
    val encoding: String,
    val tileSize: Int,
)

private fun resolveBounds(boundsOverride: Bounds?, collection: HgtCollection): Bounds {
    return boundsOverride?.clamped() ?: unionBounds(collection.tiles.map { it.extent }).clamped()
}

private fun renderTiles(
    collection: HgtCollection,
    bounds: Bounds,
    minZoom: Int,
    maxZoom: Int,
    tileSize: Int,
    workers: Int,
    consume: (Int, Int, Int, ByteArray) -> Unit,
) {
    val normalizedWorkers = workers.coerceAtLeast(1)
    val tiles = generateXyzTiles(bounds, minZoom, maxZoom).iterator()
    if (normalizedWorkers <= 1) {
        while (tiles.hasNext()) {
            val (zoom, x, y) = tiles.next()
            consume(zoom, x, y, generateTilePng(collection, zoom, x, y, tileSize))
        }
        return
    }

    val executor = Executors.newFixedThreadPool(normalizedWorkers)
    try {
        val completion = ExecutorCompletionService<Pair<Triple<Int, Int, Int>, ByteArray>>(executor)
        var pending = 0
        val maxPending = normalizedWorkers * 4

        fun submitNext(): Boolean {
            if (!tiles.hasNext()) {
                return false
            }
            val tile = tiles.next()
            completion.submit(Callable {
                tile to generateTilePng(collection, tile.first, tile.second, tile.third, tileSize)
            })
            pending += 1
            return true
        }

        while (pending < maxPending && submitNext()) {
        }

        while (pending > 0) {
            val (tile, pngData) = completion.take().get()
            pending -= 1
            consume(tile.first, tile.second, tile.third, pngData)
            while (pending < maxPending && submitNext()) {
            }
        }
    } finally {
        executor.shutdown()
    }
}

fun runConversion(options: ConversionOptions): ConversionResult {
    validateZoomRange(options.minZoom, options.maxZoom)
    val inputPaths = validateInputs(options.inputs)
    val collection = loadHgtCollection(inputPaths)
    val bounds = resolveBounds(options.bbox, collection)
    val tileCount = countXyzTiles(bounds, options.minZoom, options.maxZoom)
    val tileSize = options.tileSize.coerceAtLeast(1)

    MbtilesWriter(options.outputMbtiles).use { writer ->
        writer.writeMetadata(
            mapOf(
                "name" to options.name,
                "type" to "overlay",
                "version" to "1",
                "format" to "png",
                "bounds" to listOf(bounds.west, bounds.south, bounds.east, bounds.north).joinToString(","),
                "minzoom" to options.minZoom.toString(),
                "maxzoom" to options.maxZoom.toString(),
            )
        )
        renderTiles(collection, bounds, options.minZoom, options.maxZoom, tileSize, options.workers) { zoom, x, y, pngData ->
            writer.writeTile(zoom, x, y, pngData)
            writeTileFile(options.tileRoot, zoom, x, y, pngData)
        }
    }

    writeTileJson(
        options.tileJson,
        buildTileJson(
            bounds = bounds,
            minZoom = options.minZoom,
            maxZoom = options.maxZoom,
            tilesUrl = options.tilesUrl,
            name = options.name,
            scheme = options.scheme,
            encoding = options.encoding,
            tileSize = tileSize,
        ),
    )
    writeStyle(
        options.styleJson,
        buildStyle(
            tilesUrl = options.tilesUrl,
            sourceName = options.name,
            scheme = options.scheme,
            encoding = options.encoding,
            tileSize = tileSize,
        ),
    )

    return ConversionResult(
        bounds = bounds,
        tileCount = tileCount,
        outputMbtiles = options.outputMbtiles,
        tileJson = options.tileJson,
        styleJson = options.styleJson,
        scheme = options.scheme,
        encoding = options.encoding,
        tileSize = tileSize,
    )
}
