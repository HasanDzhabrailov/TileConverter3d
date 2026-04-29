package com.terrainconverter.core

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

const val MAX_MERCATOR_LAT: Double = 85.05112878

data class Bounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    fun clamped(): Bounds = Bounds(
        west = max(-180.0, west),
        south = max(-MAX_MERCATOR_LAT, south),
        east = min(180.0, east),
        north = min(MAX_MERCATOR_LAT, north),
    )

    fun center(): Pair<Double, Double> = Pair((west + east) / 2.0, (south + north) / 2.0)
}

fun unionBounds(extents: List<Bounds>): Bounds {
    require(extents.isNotEmpty()) { "at least one extent is required" }
    return Bounds(
        west = extents.minOf { it.west },
        south = extents.minOf { it.south },
        east = extents.maxOf { it.east },
        north = extents.maxOf { it.north },
    )
}

fun lonToTileX(lon: Double, zoom: Int): Double = ((lon + 180.0) / 360.0) * (1 shl zoom)

fun latToTileY(lat: Double, zoom: Int): Double {
    val clampedLat = lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
    val latRad = Math.toRadians(clampedLat)
    val scale = 1 shl zoom
    return (1.0 - asinh(tan(latRad)) / PI) * scale / 2.0
}

fun tileToLonLat(pixelX: Double, pixelY: Double, zoom: Int): Pair<Double, Double> {
    val scale = 1 shl zoom
    val lon = (pixelX / scale) * 360.0 - 180.0
    val n = PI * (1.0 - (2.0 * pixelY / scale))
    val lat = Math.toDegrees(atan(sinh(n)))
    return Pair(lon, lat)
}

fun tileBoundsXyz(x: Int, y: Int, zoom: Int): Bounds {
    val (west, north) = tileToLonLat(x.toDouble(), y.toDouble(), zoom)
    val (east, south) = tileToLonLat((x + 1).toDouble(), (y + 1).toDouble(), zoom)
    return Bounds(west = west, south = south, east = east, north = north)
}

data class TileRange(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

fun tileRangesForBounds(bounds: Bounds, zoom: Int): TileRange {
    val clamped = bounds.clamped()
    val epsilon = 1e-12
    val limit = (1 shl zoom) - 1
    return TileRange(
        minX = floor(lonToTileX(clamped.west, zoom)).toInt().coerceIn(0, limit),
        maxX = floor(lonToTileX(clamped.east - epsilon, zoom)).toInt().coerceIn(0, limit),
        minY = floor(latToTileY(clamped.north, zoom)).toInt().coerceIn(0, limit),
        maxY = floor(latToTileY(clamped.south + epsilon, zoom)).toInt().coerceIn(0, limit),
    )
}

fun tilesForBounds(bounds: Bounds, zoom: Int): List<Triple<Int, Int, Int>> {
    val range = tileRangesForBounds(bounds, zoom)
    val tiles = ArrayList<Triple<Int, Int, Int>>()
    for (x in range.minX..range.maxX) {
        for (y in range.minY..range.maxY) {
            tiles += Triple(zoom, x, y)
        }
    }
    return tiles
}

fun countTilesForBounds(bounds: Bounds, zoom: Int): Int {
    val range = tileRangesForBounds(bounds, zoom)
    return (range.maxX - range.minX + 1) * (range.maxY - range.minY + 1)
}
