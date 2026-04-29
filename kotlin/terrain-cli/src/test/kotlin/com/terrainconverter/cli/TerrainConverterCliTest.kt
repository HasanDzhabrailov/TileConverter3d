package com.terrainconverter.cli

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainConverterCliTest {
    @Test
    fun parsesDefaultsLikeEstablishedCliContract() {
        val args = parseCliArgs(listOf("input.hgt"))
        assertEquals(listOf("input.hgt"), args.inputs)
        assertEquals("terrain-rgb.mbtiles", args.outputMbtiles)
        assertEquals("terrain", args.tileRoot)
        assertEquals("terrain/tiles.json", args.tileJson)
        assertEquals("style.json", args.styleJson)
        assertEquals("http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png", args.tilesUrl)
        assertEquals(8, args.minZoom)
        assertEquals(12, args.maxZoom)
        assertEquals(256, args.tileSize)
        assertEquals("xyz", args.scheme)
        assertEquals("mapbox", args.encoding)
        assertEquals("terrain-dem", args.name)
        assertTrue(args.workers >= 1)
    }

    @Test
    fun parsesAllSupportedFlags() {
        val args = parseCliArgs(
            listOf(
                "first.hgt",
                "second-dir",
                "--output-mbtiles", "out.mbtiles",
                "--tile-root", "tiles",
                "--tilejson", "tiles/tiles.json",
                "--style-json", "style-custom.json",
                "--tiles-url", "https://example.test/terrain/{z}/{x}/{y}.png",
                "--minzoom", "9",
                "--maxzoom", "13",
                "--bbox", "1", "2", "3", "4",
                "--tile-size", "512",
                "--scheme", "tms",
                "--encoding", "mapbox",
                "--name", "demo",
                "--workers", "7",
            )
        )

        assertEquals(listOf("first.hgt", "second-dir"), args.inputs)
        assertEquals("out.mbtiles", args.outputMbtiles)
        assertEquals("tiles", args.tileRoot)
        assertEquals("tiles/tiles.json", args.tileJson)
        assertEquals("style-custom.json", args.styleJson)
        assertEquals("https://example.test/terrain/{z}/{x}/{y}.png", args.tilesUrl)
        assertEquals(9, args.minZoom)
        assertEquals(13, args.maxZoom)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), args.bbox)
        assertEquals(512, args.tileSize)
        assertEquals("tms", args.scheme)
        assertEquals("mapbox", args.encoding)
        assertEquals("demo", args.name)
        assertEquals(7, args.workers)
    }

    @Test
    fun cliMainWritesParityArtifacts() {
        val inputDir = Files.createTempDirectory("terrain-cli-input-")
        val source = inputDir.resolve("N00E000.hgt")
        writeHgt(source, fill = 25)

        val outputDir = Files.createTempDirectory("terrain-cli-output-")
        main(
            arrayOf(
                source.toString(),
                "--output-mbtiles", outputDir.resolve("terrain-rgb.mbtiles").toString(),
                "--tile-root", outputDir.resolve("terrain").toString(),
                "--tilejson", outputDir.resolve("terrain").resolve("tiles.json").toString(),
                "--style-json", outputDir.resolve("style.json").toString(),
                "--minzoom", "8",
                "--maxzoom", "8",
                "--workers", "2",
            )
        )

        assertTrue(Files.exists(outputDir.resolve("terrain-rgb.mbtiles")))
        assertTrue(Files.exists(outputDir.resolve("terrain").resolve("tiles.json")))
        assertTrue(Files.exists(outputDir.resolve("style.json")))
        val tileFiles = Files.walk(outputDir.resolve("terrain")).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".png") }
                .toList()
        }
        assertTrue(tileFiles.isNotEmpty())
        assertTrue(outputDir.resolve("terrain").resolve("tiles.json").readText().contains("\"scheme\": \"xyz\""))
        assertTrue(outputDir.resolve("style.json").readText().contains("\"terrain-dem\""))
    }

    @Test
    fun workerCountDoesNotChangeRenderedPngOutputs() {
        val inputDir = Files.createTempDirectory("terrain-cli-workers-input-")
        val source = inputDir.resolve("N00E000.hgt")
        writeHgt(source, fill = 50)

        val sequentialDir = Files.createTempDirectory("terrain-cli-workers-seq-")
        val threadedDir = Files.createTempDirectory("terrain-cli-workers-threaded-")

        runCliConversion(source, sequentialDir, workers = 1)
        runCliConversion(source, threadedDir, workers = 3)

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

private fun runCliConversion(input: Path, outputDir: Path, workers: Int) {
    main(
        arrayOf(
            input.toString(),
            "--output-mbtiles", outputDir.resolve("terrain-rgb.mbtiles").toString(),
            "--tile-root", outputDir.resolve("terrain").toString(),
            "--tilejson", outputDir.resolve("terrain").resolve("tiles.json").toString(),
            "--style-json", outputDir.resolve("style.json").toString(),
            "--minzoom", "8",
            "--maxzoom", "8",
            "--workers", workers.toString(),
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
