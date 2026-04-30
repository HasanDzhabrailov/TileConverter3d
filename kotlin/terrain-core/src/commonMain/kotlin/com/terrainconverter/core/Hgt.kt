package com.terrainconverter.core

import kotlin.math.floor

const val VOID_VALUE: Int = -32768

val SUPPORTED_GRID_SIZES: Map<Long, Int> = mapOf(
    1201L * 1201L * 2L to 1201,
    3601L * 3601L * 2L to 3601,
)

data class HgtCoordinate(val lat: Int, val lon: Int)

/**
 * HGT tile data structure for elevation data.
 * Platform-neutral - works with in-memory grid data.
 */
data class HgtTile(
    val south: Int,
    val west: Int,
    val size: Int,
    val grid: ShortArray,
    val voidValue: Int = VOID_VALUE,
) {
    val north: Int get() = south + 1
    val east: Int get() = west + 1
    val resolution: Int get() = size - 1
    val extent: Bounds get() = Bounds(west.toDouble(), south.toDouble(), east.toDouble(), north.toDouble())

    private fun index(row: Int, col: Int): Int = (row * size) + col
    private fun sampleValue(index: Int): Int = grid[index].toInt()

    fun covers(lon: Double, lat: Double): Boolean = west <= lon && lon < east && south <= lat && lat < north

    fun sampleBilinear(lon: Double, lat: Double): Double? {
        if (!covers(lon, lat)) {
            return null
        }

        val clampedLon = lon.coerceIn(west.toDouble(), nextAfter(east.toDouble(), west.toDouble()))
        val clampedLat = lat.coerceIn(south.toDouble(), nextAfter(north.toDouble(), south.toDouble()))

        val colF = (clampedLon - west) * resolution
        val rowF = (north - clampedLat) * resolution

        val col0 = floor(colF).toInt()
        val row0 = floor(rowF).toInt()
        val col1 = minOf(col0 + 1, size - 1)
        val row1 = minOf(row0 + 1, size - 1)

        val fx = colF - col0
        val fy = rowF - row0

        val value00 = sampleValue(index(row0, col0))
        val value10 = sampleValue(index(row0, col1))
        val value01 = sampleValue(index(row1, col0))
        val value11 = sampleValue(index(row1, col1))

        var weighted = 0.0
        var totalWeight = 0.0
        var fallback: Double? = null

        fun take(value: Int, weight: Double) {
            if (value == voidValue) {
                return
            }
            if (fallback == null) {
                fallback = value.toDouble()
            }
            weighted += value.toDouble() * weight
            totalWeight += weight
        }

        take(value00, (1.0 - fx) * (1.0 - fy))
        take(value10, fx * (1.0 - fy))
        take(value01, (1.0 - fx) * fy)
        take(value11, fx * fy)

        return if (totalWeight > 0.0) weighted / totalWeight else fallback
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as HgtTile
        return south == other.south && west == other.west && size == other.size &&
                voidValue == other.voidValue && grid.contentEquals(other.grid)
    }

    override fun hashCode(): Int {
        var result = south
        result = 31 * result + west
        result = 31 * result + size
        result = 31 * result + grid.contentHashCode()
        result = 31 * result + voidValue
        return result
    }
}

/**
 * Collection of HGT tiles covering a geographic area.
 * Platform-neutral - works with in-memory tile data.
 */
class HgtCollection(val tiles: List<HgtTile>) : ElevationSampler {
    init {
        require(tiles.isNotEmpty()) { "at least one HGT tile is required" }
    }

    private val tileMap: Map<Pair<Int, Int>, HgtTile> = tiles.associateBy { Pair(it.south, it.west) }
    override val bounds: Bounds = unionBounds(tiles.map { it.extent })

    override fun sample(lon: Double, lat: Double): Double? {
        if (!(bounds.west <= lon && lon < bounds.east && bounds.south <= lat && lat < bounds.north)) {
            return null
        }
        val tile = tileMap[Pair(floor(lat).toInt(), floor(lon).toInt())] ?: return null
        return tile.sampleBilinear(lon, lat)
    }

    override fun sampleGrid(width: Int, height: Int, lon: DoubleArray, lat: DoubleArray): GridSampleResult {
        require(width >= 0) { "width must be >= 0" }
        require(height >= 0) { "height must be >= 0" }
        require(lon.size == lat.size) { "lon and lat grids must have the same shape" }
        require(lon.size == width * height) { "grid dimensions do not match lon/lat data" }

        val values = DoubleArray(lon.size)
        val valid = BooleanArray(lon.size)
        var currentKey: Pair<Int, Int>? = null
        var currentTile: HgtTile? = null
        for (index in lon.indices) {
            val sampleLon = lon[index]
            val sampleLat = lat[index]
            if (!(bounds.west <= sampleLon && sampleLon < bounds.east && bounds.south <= sampleLat && sampleLat < bounds.north)) {
                continue
            }
            val key = Pair(floor(sampleLat).toInt(), floor(sampleLon).toInt())
            val tile = if (currentKey == key) {
                currentTile
            } else {
                tileMap[key].also {
                    currentKey = key
                    currentTile = it
                }
            } ?: continue
            val value = tile.sampleBilinear(sampleLon, sampleLat) ?: continue
            values[index] = value
            valid[index] = true
        }
        return GridSampleResult(values = values, valid = valid, width = width, height = height)
    }
}

interface ElevationSampler {
    val bounds: Bounds

    fun sample(lon: Double, lat: Double): Double?

    fun sampleGrid(width: Int, height: Int, lon: DoubleArray, lat: DoubleArray): GridSampleResult {
        require(width >= 0) { "width must be >= 0" }
        require(height >= 0) { "height must be >= 0" }
        require(lon.size == lat.size) { "lon and lat grids must have the same shape" }
        require(lon.size == width * height) { "grid dimensions do not match lon/lat data" }

        val values = DoubleArray(lon.size)
        val valid = BooleanArray(lon.size)
        for (index in lon.indices) {
            val value = sample(lon[index], lat[index])
            if (value != null) {
                values[index] = value
                valid[index] = true
            }
        }
        return GridSampleResult(values = values, valid = valid, width = width, height = height)
    }
}

/**
 * Platform-neutral nextAfter implementation.
 */
internal expect fun nextAfter(start: Double, direction: Double): Double

/**
 * Parse HGT filename to extract coordinates.
 * Platform-neutral logic.
 */
fun parseHgtCoordinate(filename: String): HgtCoordinate {
    val match = HGT_NAME_RE.matchEntire(filename)
        ?: throw IllegalArgumentException("invalid HGT filename: $filename")
    var lat = match.groups[2]!!.value.toInt()
    var lon = match.groups[4]!!.value.toInt()
    if (match.groups[1]!!.value.equals("S", ignoreCase = true)) {
        lat *= -1
    }
    if (match.groups[3]!!.value.equals("W", ignoreCase = true)) {
        lon *= -1
    }
    return HgtCoordinate(lat = lat, lon = lon)
}

/**
 * Infer HGT grid size from file size in bytes.
 * Platform-neutral logic.
 */
fun inferHgtSize(sizeBytes: Long): Int {
    return SUPPORTED_GRID_SIZES[sizeBytes]
        ?: throw IllegalArgumentException("unsupported HGT file size: ${sizeBytes} bytes")
}

private val HGT_NAME_RE = Regex("^([NS])(\\d{2})([EW])(\\d{3})\\.hgt$", RegexOption.IGNORE_CASE)
