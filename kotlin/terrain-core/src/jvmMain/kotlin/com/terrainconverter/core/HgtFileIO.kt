package com.terrainconverter.core

import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.math.floor

private val HGT_NAME_RE = Regex("^(?<latNs>[NS])(?<lat>\\d{2})(?<lonEw>[EW])(?<lon>\\d{3})\\.hgt$", RegexOption.IGNORE_CASE)

/**
 * JVM implementation of nextAfter using java.lang.Math
 */
internal actual fun nextAfter(start: Double, direction: Double): Double = 
    Math.nextAfter(start, direction)

/**
 * Parse HGT coordinate from a Path.
 * JVM-specific: uses Path type.
 */
fun parseHgtCoordinate(path: Path): HgtCoordinate {
    val match = HGT_NAME_RE.matchEntire(path.name) ?: throw IllegalArgumentException("invalid HGT filename: ${path.name}")
    var lat = match.groups["lat"]!!.value.toInt()
    var lon = match.groups["lon"]!!.value.toInt()
    if (match.groups["latNs"]!!.value.equals("S", ignoreCase = true)) {
        lat *= -1
    }
    if (match.groups["lonEw"]!!.value.equals("W", ignoreCase = true)) {
        lon *= -1
    }
    return HgtCoordinate(lat = lat, lon = lon)
}

/**
 * Infer HGT grid size from a file Path.
 * JVM-specific: uses Path and Files API.
 */
fun inferHgtSize(path: Path): Int {
    val sizeBytes = Files.size(path)
    return com.terrainconverter.core.inferHgtSize(sizeBytes)
}

/**
 * Read an HGT file from disk.
 * JVM-specific: file I/O operation.
 */
fun readHgt(path: Path): HgtTile {
    val coordinate = parseHgtCoordinate(path)
    val size = inferHgtSize(path)
    val sampleCount = size * size
    val grid = ShortArray(sampleCount)
    path.inputStream().buffered().use { input ->
        for (index in 0 until sampleCount) {
            val high = input.read()
            val low = input.read()
            if (high < 0 || low < 0) {
                throw EOFException("unexpected EOF while reading HGT samples: $path")
            }
            grid[index] = (((high shl 8) or low) and 0xFFFF).toShort()
        }
    }
    return HgtTile(south = coordinate.lat, west = coordinate.lon, size = size, grid = grid)
}

/**
 * Load a collection of HGT files.
 * JVM-specific: file I/O operation.
 */
fun loadHgtCollection(paths: List<Path>): HgtCollection = HgtCollection(paths.sorted().map(::readHgt))

/**
 * Validate and discover HGT input paths.
 * JVM-specific: uses Path, Files API, directory walking.
 */
fun validateInputs(inputs: List<Path>): List<Path> {
    require(inputs.isNotEmpty()) { "no HGT inputs were provided" }
    val discovered = ArrayList<Path>()
    val seenCoords = HashSet<Pair<Int, Int>>()
    val seenSizes = HashSet<Int>()

    for (path in inputs) {
        when {
            path.isDirectory() -> {
                Files.walk(path).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension.equals("hgt", ignoreCase = true) }
                        .sorted()
                        .forEach { discovered.add(it) }
                }
            }
            path.isRegularFile() -> discovered.add(path)
            else -> throw IllegalArgumentException("input path does not exist: $path")
        }
    }

    require(discovered.isNotEmpty()) { "no .hgt files found in inputs" }

    val uniquePaths = ArrayList<Path>()
    val seenPaths = LinkedHashSet<Path>()
    for (path in discovered) {
        if (!seenPaths.add(path)) {
            continue
        }
        val coordinate = parseHgtCoordinate(path)
        val key = Pair(coordinate.lat, coordinate.lon)
        require(seenCoords.add(key)) { "duplicate HGT tile for coordinate $key: $path" }
        seenSizes += inferHgtSize(path)
        uniquePaths.add(path)
    }

    require(seenSizes.size <= 1) { "mixed HGT resolutions are not supported in one run" }
    return uniquePaths
}

/**
 * Validate zoom range.
 * Platform-neutral but kept here for consistency with other validation functions.
 */
fun validateZoomRange(minZoom: Int, maxZoom: Int) {
    require(minZoom >= 0) { "min zoom must be >= 0" }
    require(maxZoom >= minZoom) { "max zoom must be >= min zoom" }
}
