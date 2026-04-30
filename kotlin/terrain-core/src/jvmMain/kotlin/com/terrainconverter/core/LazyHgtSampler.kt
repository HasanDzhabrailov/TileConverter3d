package com.terrainconverter.core

import java.nio.file.Path
import kotlin.math.floor

private data class HgtTileDescriptor(
    val path: Path,
    val south: Int,
    val west: Int,
    val size: Int,
) {
    val extent: Bounds = Bounds(west.toDouble(), south.toDouble(), west + 1.0, south + 1.0)
}

private class TileCache(private val maxEntries: Int) {
    private val cache = object : LinkedHashMap<Pair<Int, Int>, HgtTile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, HgtTile>?): Boolean = size > maxEntries
    }

    fun getOrLoad(key: Pair<Int, Int>, loader: () -> HgtTile): HgtTile = synchronized(cache) {
        cache[key] ?: loader().also { cache[key] = it }
    }
}

private class LazyHgtSampler(
    descriptors: List<HgtTileDescriptor>,
    cacheEntries: Int,
) : ElevationSampler {
    private data class ThreadLocalTile(val key: Pair<Int, Int>, val tile: HgtTile)

    private val tileMap = descriptors.associateBy { Pair(it.south, it.west) }
    private val cache = TileCache(cacheEntries.coerceAtLeast(1))
    private val threadLocalTile = ThreadLocal<ThreadLocalTile?>()

    override val bounds: Bounds = unionBounds(descriptors.map { it.extent })

    override fun sample(lon: Double, lat: Double): Double? {
        if (!(bounds.west <= lon && lon < bounds.east && bounds.south <= lat && lat < bounds.north)) {
            return null
        }
        val key = Pair(floor(lat).toInt(), floor(lon).toInt())
        val cached = threadLocalTile.get()
        if (cached != null && cached.key == key) {
            return cached.tile.sampleBilinear(lon, lat)
        }
        val descriptor = tileMap[key] ?: return null
        val tile = cache.getOrLoad(key) { readHgt(descriptor.path) }
        threadLocalTile.set(ThreadLocalTile(key, tile))
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
                val descriptor = tileMap[key]
                val loaded = descriptor?.let { cache.getOrLoad(key) { readHgt(it.path) } }
                currentKey = key
                currentTile = loaded
                loaded
            } ?: continue
            val value = tile.sampleBilinear(sampleLon, sampleLat) ?: continue
            values[index] = value
            valid[index] = true
        }
        currentKey?.let { key ->
            currentTile?.let { tile -> threadLocalTile.set(ThreadLocalTile(key, tile)) }
        }
        return GridSampleResult(values = values, valid = valid, width = width, height = height)
    }
}

private const val DEFAULT_HGT_CACHE_SIZE = 16

fun loadOnDemandHgtSampler(paths: List<Path>, cacheEntries: Int = DEFAULT_HGT_CACHE_SIZE): ElevationSampler {
    val descriptors = paths.sorted().map { path ->
        val coordinate = parseHgtCoordinate(path)
        HgtTileDescriptor(
            path = path,
            south = coordinate.lat,
            west = coordinate.lon,
            size = inferHgtSize(path),
        )
    }
    return LazyHgtSampler(descriptors, cacheEntries)
}
