package com.terrainconverter.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LazyHgtSamplerTest {
    @Test
    fun onDemandSamplerMatchesInMemorySamplingAcrossTiles() {
        val dir = Files.createTempDirectory("terrain-core-lazy-")
        val westTile = dir.resolve("N00E000.hgt")
        val eastTile = dir.resolve("N00E001.hgt")
        writeHgt(westTile, fill = 10)
        writeHgt(eastTile, fill = 20)

        val eager = loadHgtCollection(listOf(westTile, eastTile))
        val lazy = loadOnDemandHgtSampler(listOf(westTile, eastTile), cacheEntries = 1)

        assertEquals(eager.bounds, lazy.bounds)
        assertEquals(eager.sample(0.25, 0.5), lazy.sample(0.25, 0.5))
        assertEquals(eager.sample(1.25, 0.5), lazy.sample(1.25, 0.5))
        assertNull(lazy.sample(2.25, 0.5))
    }
}

private fun writeHgt(path: Path, size: Int = 1201, fill: Int = 0) {
    val samples = ShortArray(size * size) { fill.toShort() }
    val bytes = ByteArray(samples.size * 2)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    samples.forEach(buffer::putShort)
    Files.write(path, bytes)
}
