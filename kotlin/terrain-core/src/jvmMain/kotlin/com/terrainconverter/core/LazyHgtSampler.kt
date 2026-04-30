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
    private val tileMap = descriptors.associateBy { Pair(it.south, it.west) }
    private val cache = TileCache(cacheEntries.coerceAtLeast(1))

    override val bounds: Bounds = unionBounds(descriptors.map { it.extent })

    override fun sample(lon: Double, lat: Double): Double? {
        if (!(bounds.west <= lon && lon < bounds.east && bounds.south <= lat && lat < bounds.north)) {
            return null
        }
        val key = Pair(floor(lat).toInt(), floor(lon).toInt())
        val descriptor = tileMap[key] ?: return null
        val tile = cache.getOrLoad(key) { readHgt(descriptor.path) }
        return tile.sampleBilinear(lon, lat)
    }
}

private const val DEFAULT_HGT_CACHE_SIZE = 8

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
