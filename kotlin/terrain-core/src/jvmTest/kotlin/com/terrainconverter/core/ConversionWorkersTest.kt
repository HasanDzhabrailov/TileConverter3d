package com.terrainconverter.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversionWorkersTest {
    @Test
    fun workerCountDoesNotChangeFilesystemOutputs() {
        val inputDir = Files.createTempDirectory("terrain-core-workers-input-")
        val source = inputDir.resolve("N00E000.hgt")
        writeHgt(source, fill = 40)

        val sequentialDir = Files.createTempDirectory("terrain-core-workers-seq-")
        val threadedDir = Files.createTempDirectory("terrain-core-workers-threaded-")

        runCoreConversion(source, sequentialDir, workers = 1)
        runCoreConversion(source, threadedDir, workers = 3)

        val sequentialTiles = tileFileMap(sequentialDir.resolve("terrain"))
        val threadedTiles = tileFileMap(threadedDir.resolve("terrain"))
        assertEquals(sequentialTiles.keys, threadedTiles.keys)
        for (relativePath in sequentialTiles.keys) {
            assertTrue(sequentialTiles.getValue(relativePath).contentEquals(threadedTiles.getValue(relativePath)))
        }
        assertEquals(
            sequentialDir.resolve("terrain").resolve("tiles.json").readText(),
            threadedDir.resolve("terrain").resolve("tiles.json").readText(),
        )
        assertEquals(
            sequentialDir.resolve("style.json").readText(),
            threadedDir.resolve("style.json").readText(),
        )
    }
}

private fun runCoreConversion(input: Path, outputDir: Path, workers: Int) {
    runConversion(
        ConversionOptions(
            inputs = listOf(input),
            outputMbtiles = outputDir.resolve("terrain-rgb.mbtiles"),
            tileRoot = outputDir.resolve("terrain"),
            tileJson = outputDir.resolve("terrain").resolve("tiles.json"),
            styleJson = outputDir.resolve("style.json"),
            tilesUrl = "http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png",
            minZoom = 8,
            maxZoom = 8,
            workers = workers,
        )
    )
}

private fun tileFileMap(tileRoot: Path): Map<String, ByteArray> = Files.walk(tileRoot).use { stream ->
    stream.filter { Files.isRegularFile(it) }
        .filter { it.fileName.toString().endsWith(".png") }
        .toList()
        .associate { tileRoot.relativize(it).toString() to it.readBytes() }
}

private fun writeHgt(path: Path, size: Int = 1201, fill: Int = 0) {
    val samples = ShortArray(size * size) { fill.toShort() }
    val bytes = ByteArray(samples.size * 2)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    samples.forEach(buffer::putShort)
    Files.write(path, bytes)
}
