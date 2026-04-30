package com.terrainconverter.core

import java.nio.file.Files
import java.nio.file.Path

fun generateTileRgba(collection: ElevationSampler, zoom: Int, x: Int, y: Int, tileSize: Int = 256): ByteArray {
    val sampleCount = tileSize * tileSize
    val lon = DoubleArray(sampleCount)
    val lat = DoubleArray(sampleCount)
    val lonByColumn = DoubleArray(tileSize)
    val latByRow = DoubleArray(tileSize)
    for (px in 0 until tileSize) {
        lonByColumn[px] = tileToLonLat(x + ((px + 0.5) / tileSize.toDouble()), y.toDouble(), zoom).first
    }
    for (py in 0 until tileSize) {
        latByRow[py] = tileToLonLat(x.toDouble(), y + ((py + 0.5) / tileSize.toDouble()), zoom).second
    }
    for (py in 0 until tileSize) {
        val sampleLat = latByRow[py]
        for (px in 0 until tileSize) {
            val index = (py * tileSize) + px
            lon[index] = lonByColumn[px]
            lat[index] = sampleLat
        }
    }

    val samples = collection.sampleGrid(tileSize, tileSize, lon, lat)
    val pixels = ByteArray(sampleCount * 4)
    for (index in 0 until sampleCount) {
        if (!samples.valid[index]) {
            continue
        }
        val pixelOffset = index * 4
        val encoded = encodeElevation(samples.values[index])
        pixels[pixelOffset] = encoded.red.toByte()
        pixels[pixelOffset + 1] = encoded.green.toByte()
        pixels[pixelOffset + 2] = encoded.blue.toByte()
        pixels[pixelOffset + 3] = 0xFF.toByte()
    }
    return pixels
}

fun generateTilePng(collection: ElevationSampler, zoom: Int, x: Int, y: Int, tileSize: Int = 256): ByteArray =
    writePngRgba(tileSize, tileSize, generateTileRgba(collection, zoom, x, y, tileSize))

fun countXyzTiles(bounds: Bounds, minZoom: Int, maxZoom: Int): Int = (minZoom..maxZoom).sumOf { countTilesForBounds(bounds, it) }

fun generateXyzTiles(bounds: Bounds, minZoom: Int, maxZoom: Int): Sequence<Triple<Int, Int, Int>> = sequence {
    for (zoom in minZoom..maxZoom) {
        for (tile in tilesForBounds(bounds, zoom)) {
            yield(tile)
        }
    }
}

fun writeTileFile(tileRoot: Path, zoom: Int, x: Int, y: Int, tileData: ByteArray): Path {
    val tilePath = tileRoot.resolve(zoom.toString()).resolve(x.toString()).resolve("$y.png")
    Files.createDirectories(tilePath.parent)
    Files.write(tilePath, tileData)
    return tilePath
}
