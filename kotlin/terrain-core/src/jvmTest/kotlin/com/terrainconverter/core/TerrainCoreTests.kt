package com.terrainconverter.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.zip.Inflater
import kotlin.math.abs

private fun assertEquals(expected: Any?, actual: Any?, message: String = "") {
    check(expected == actual) { "Expected <$expected> but was <$actual>. $message" }
}

private fun assertTrue(value: Boolean, message: String = "") {
    check(value) { message.ifBlank { "Expected condition to be true" } }
}

private fun assertApprox(expected: Double, actual: Double, tolerance: Double = 1e-9) {
    check(abs(expected - actual) <= tolerance) { "Expected <$expected> but was <$actual> within $tolerance" }
}

private fun writeHgt(path: Path, size: Int = 1201, fill: Int = 0, updates: List<Triple<Int, Int, Int>> = emptyList()) {
    val samples = ShortArray(size * size) { fill.toShort() }
    for ((row, col, value) in updates) {
        samples[(row * size) + col] = value.toShort()
    }
    val bytes = ByteArray(samples.size * 2)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    samples.forEach(buffer::putShort)
    Files.write(path, bytes)
}

private fun decodePngRgba(pngBytes: ByteArray): Triple<Int, Int, ByteArray> {
    assertTrue(pngBytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
    var offset = 8
    var width = 0
    var height = 0
    val idat = ByteArrayOutputStream()
    while (offset < pngBytes.size) {
        val length = ByteBuffer.wrap(pngBytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
        val chunkType = String(pngBytes, offset + 4, 4, Charsets.US_ASCII)
        val dataOffset = offset + 8
        val data = pngBytes.copyOfRange(dataOffset, dataOffset + length)
        offset += 12 + length
        when (chunkType) {
            "IHDR" -> {
                width = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                height = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.BIG_ENDIAN).int
            }
            "IDAT" -> idat.write(data)
            "IEND" -> break
        }
    }
    val inflater = Inflater()
    inflater.setInput(idat.toByteArray())
    val raw = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        if (count == 0 && inflater.needsInput()) {
            break
        }
        raw.write(buffer, 0, count)
    }
    inflater.end()
    val rawBytes = raw.toByteArray()
    val stride = (width * 4) + 1
    val rgba = ByteArray(width * height * 4)
    for (row in 0 until height) {
        val rowOffset = row * stride
        assertEquals(0.toByte(), rawBytes[rowOffset])
        System.arraycopy(rawBytes, rowOffset + 1, rgba, row * width * 4, width * 4)
    }
    return Triple(width, height, rgba)
}

private fun sqliteTileCount(dbPath: Path): Int {
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM tiles").use { rows ->
                check(rows.next())
                return rows.getInt(1)
            }
        }
    }
}

private fun sqliteMetadataRows(dbPath: Path): List<Pair<String, String>> {
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name, value FROM metadata ORDER BY name").use { rows ->
                val values = mutableListOf<Pair<String, String>>()
                while (rows.next()) {
                    values += rows.getString(1) to rows.getString(2)
                }
                return values
            }
        }
    }
}

private fun sqliteTileRows(dbPath: Path): List<List<Any>> {
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles").use { rows ->
                val values = mutableListOf<List<Any>>()
                while (rows.next()) {
                    values += listOf(rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getBytes(4).decodeToString())
                }
                return values
            }
        }
    }
}

object TerrainCoreTests {
    fun runAll() {
        testUnionBounds()
        testTilesForExactTileBounds()
        testEncodeDecodeZero()
        testEncodeDecodeSignedElevations()
        testParseCoordinate()
        testInferSizeAndOrientation()
        testValidateInputsDiscoversUppercaseHgt()
        testSampleGridMatchesScalarSampling()
        testGenerateTileRgbaUniformValue()
        testGenerateTilePngPreservesNorthSouthGradient()
        testGenerateTileRgbaTransparentOutsideDemBounds()
        testGenerateTileRgbaTransparentForVoidSamples()
        testTileJsonFields()
        testTileJsonCustomSchemeAndTileSize()
        testStyleFields()
        testStyleCustomSchemeAndTileSize()
        testXyzToTmsRow()
        testRunConversionWritesOutputs()
        testMbtilesWriterStoresMetadataAndTile()
    }

    private fun testUnionBounds() {
        val bounds = unionBounds(listOf(Bounds(10.0, 20.0, 11.0, 21.0), Bounds(11.0, 19.5, 12.0, 20.5)))
        assertEquals(Bounds(10.0, 19.5, 12.0, 21.0), bounds)
    }

    private fun testTilesForExactTileBounds() {
        val bounds = tileBoundsXyz(1, 1, 1)
        assertEquals(listOf(Triple(1, 1, 1)), tilesForBounds(bounds, 1))
    }

    private fun testEncodeDecodeZero() {
        val encoded = encodeElevation(0.0)
        assertEquals(Rgb(1, 134, 160), encoded)
        assertApprox(0.0, decodeElevation(encoded.red, encoded.green, encoded.blue))
    }

    private fun testEncodeDecodeSignedElevations() {
        for (elevation in listOf(-123.4, 456.7)) {
            val decoded = encodeElevation(elevation).let { decodeElevation(it.red, it.green, it.blue) }
            assertApprox(elevation, decoded)
        }
    }

    private fun testParseCoordinate() {
        val coordinate = parseHgtCoordinate(Path.of("S12W123.hgt"))
        assertEquals(-12, coordinate.lat)
        assertEquals(-123, coordinate.lon)
    }

    private fun testInferSizeAndOrientation() {
        val dir = Files.createTempDirectory("terrain-core-hgt-")
        val hgtPath = dir.resolve("N00E000.hgt")
        writeHgt(
            hgtPath,
            updates = listOf(
                Triple(0, 0, 1000),
                Triple(0, 1200, 2000),
                Triple(1200, 0, -100),
                Triple(1200, 1200, 300),
            ),
        )
        assertEquals(1201, inferHgtSize(hgtPath))
        val tile = readHgt(hgtPath)
        assertApprox(1000.0, tile.sampleBilinear(0.0, Math.nextAfter(1.0, 0.0))!!, 1e-6)
        assertEquals(null, tile.sampleBilinear(1.0, 1.0))
        assertApprox(2000.0, tile.sampleBilinear(0.999999999999, 0.999999999999)!!, 1e-5)
        assertApprox(-100.0, tile.sampleBilinear(0.0, 0.0)!!)
        assertApprox(300.0, tile.sampleBilinear(0.999999999999, 0.0)!!, 1e-5)
    }

    private fun testValidateInputsDiscoversUppercaseHgt() {
        val dir = Files.createTempDirectory("terrain-core-validate-")
        val hgtPath = dir.resolve("N00E000.HGT")
        writeHgt(hgtPath)
        assertEquals(listOf(hgtPath), validateInputs(listOf(dir)))
    }

    private fun testSampleGridMatchesScalarSampling() {
        val dir = Files.createTempDirectory("terrain-core-sample-grid-")
        val hgtPath = dir.resolve("N00E000.hgt")
        writeHgt(
            hgtPath,
            updates = listOf(
                Triple(0, 0, 1000),
                Triple(0, 1200, 2000),
                Triple(1200, 0, -100),
                Triple(1200, 1200, 300),
            ),
        )
        val tile = readHgt(hgtPath)
        val lon = doubleArrayOf(0.0, 0.999999999999, 0.0, 0.999999999999)
        val lat = doubleArrayOf(Math.nextAfter(1.0, 0.0), 0.999999999999, 0.0, 0.0)

        val (values, valid) = tile.sampleBilinearGrid(lon, lat)

        assertEquals(listOf(true, true, true, true), valid.toList())
        for (index in lon.indices) {
            assertApprox(tile.sampleBilinear(lon[index], lat[index])!!, values[index])
        }
    }

    private fun testGenerateTileRgbaUniformValue() {
        val dir = Files.createTempDirectory("terrain-core-tiling-")
        val hgtPath = dir.resolve("N00E000.hgt")
        writeHgt(hgtPath, fill = 50)
        val collection = loadHgtCollection(listOf(hgtPath))
        val bounds = unionBounds(collection.tiles.map { it.extent })
        val (zoom, x, y) = tilesForBounds(bounds, 8).first()
        val rgba = generateTileRgba(collection, zoom, x, y, 8)
        val expected = byteArrayOf(encodeElevation(50.0).red.toByte(), encodeElevation(50.0).green.toByte(), encodeElevation(50.0).blue.toByte(), 0xFF.toByte())
        val pixels = rgba.asList().chunked(4).map { chunk -> byteArrayOf(chunk[0], chunk[1], chunk[2], chunk[3]) }
        val opaque = pixels.filter { it[3] == 0xFF.toByte() }
        val transparent = pixels.filter { it[3] == 0.toByte() }
        assertTrue(opaque.isNotEmpty())
        assertTrue(transparent.isNotEmpty())
        assertTrue(opaque.all { it.contentEquals(expected) })
        assertTrue(transparent.all { it.contentEquals(byteArrayOf(0, 0, 0, 0)) })
    }

    private fun testGenerateTilePngPreservesNorthSouthGradient() {
        val dir = Files.createTempDirectory("terrain-core-png-")
        val hgtPath = dir.resolve("N00E000.hgt")
        val size = 1201
        val updates = ArrayList<Triple<Int, Int, Int>>(size * size)
        for (row in 0 until size) {
            val value = 1000 - ((1000 * row) / (size - 1))
            for (col in 0 until size) {
                updates += Triple(row, col, value)
            }
        }
        writeHgt(hgtPath, updates = updates)
        val collection = loadHgtCollection(listOf(hgtPath))
        val bounds = unionBounds(collection.tiles.map { it.extent })
        val (zoom, x, y) = tilesForBounds(bounds, 8).first()
        val pngData = generateTilePng(collection, zoom, x, y, 8)
        val (width, height, rgba) = decodePngRgba(pngData)
        var top: Double? = null
        var bottom: Double? = null
        for (row in 0 until height) {
            for (col in 0 until width) {
                val offset = ((row * width) + col) * 4
                if (rgba[offset + 3] == 0xFF.toByte()) {
                    top = decodeElevation(rgba[offset].toUByte().toInt(), rgba[offset + 1].toUByte().toInt(), rgba[offset + 2].toUByte().toInt())
                    break
                }
            }
            if (top != null) break
        }
        for (row in height - 1 downTo 0) {
            for (col in 0 until width) {
                val offset = ((row * width) + col) * 4
                if (rgba[offset + 3] == 0xFF.toByte()) {
                    bottom = decodeElevation(rgba[offset].toUByte().toInt(), rgba[offset + 1].toUByte().toInt(), rgba[offset + 2].toUByte().toInt())
                    break
                }
            }
            if (bottom != null) break
        }
        assertTrue(top != null && bottom != null)
        assertTrue(top!! > bottom!!)
    }

    private fun testGenerateTileRgbaTransparentOutsideDemBounds() {
        val dir = Files.createTempDirectory("terrain-core-transparent-")
        val hgtPath = dir.resolve("N00E000.hgt")
        writeHgt(hgtPath, fill = 50)
        val collection = loadHgtCollection(listOf(hgtPath))
        val bounds = unionBounds(collection.tiles.map { it.extent })
        val (zoom, x, y) = tilesForBounds(bounds, 8).first()

        val rgba = generateTileRgba(collection, zoom, x, y, 32)
        val alphas = rgba.filterIndexed { index, _ -> index % 4 == 3 }

        assertTrue(alphas.contains(0))
        assertTrue(alphas.contains(0xFF.toByte()))
    }

    private fun testGenerateTileRgbaTransparentForVoidSamples() {
        val dir = Files.createTempDirectory("terrain-core-void-")
        val hgtPath = dir.resolve("N00E000.hgt")
        writeHgt(hgtPath, fill = VOID_VALUE)
        val collection = loadHgtCollection(listOf(hgtPath))
        val bounds = unionBounds(collection.tiles.map { it.extent })
        val (zoom, x, y) = tilesForBounds(bounds, 8).first()
        val rgba = generateTileRgba(collection, zoom, x, y, 8)
        assertTrue(rgba.asList().chunked(4).all { it == listOf<Byte>(0, 0, 0, 0) })
    }

    private fun testTileJsonFields() {
        val tileJson = buildTileJson(Bounds(10.0, 20.0, 11.0, 21.0), 8, 12, "http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png")
        assertEquals("raster-dem", tileJson["type"])
        assertEquals("mapbox", tileJson["encoding"])
        assertEquals(256, tileJson["tileSize"])
        assertEquals("xyz", tileJson["scheme"])
    }

    private fun testTileJsonCustomSchemeAndTileSize() {
        val tileJson = buildTileJson(
            Bounds(10.0, 20.0, 11.0, 21.0),
            8,
            12,
            "http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png",
            scheme = "tms",
            tileSize = 512,
        )
        assertEquals("tms", tileJson["scheme"])
        assertEquals(512, tileJson["tileSize"])
    }

    private fun testStyleFields() {
        val style = buildStyle("http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png")
        val source = (style["sources"] as Map<*, *>)["terrain-dem"] as Map<*, *>
        assertEquals("raster-dem", source["type"])
        assertEquals("mapbox", source["encoding"])
        val terrain = style["terrain"] as Map<*, *>
        assertEquals("terrain-dem", terrain["source"])
    }

    private fun testStyleCustomSchemeAndTileSize() {
        val style = buildStyle(
            "http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png",
            scheme = "tms",
            tileSize = 512,
        )
        val source = (style["sources"] as Map<*, *>)["terrain-dem"] as Map<*, *>
        assertEquals("tms", source["scheme"])
        assertEquals(512, source["tileSize"])
    }

    private fun testXyzToTmsRow() {
        assertEquals(5, xyzToTmsRow(3, 2))
    }

    private fun testRunConversionWritesOutputs() {
        val dir = Files.createTempDirectory("terrain-core-run-")
        val hgtPath = dir.resolve("N00E000.hgt")
        writeHgt(hgtPath, fill = 25)

        val result = runConversion(
            ConversionOptions(
                inputs = listOf(hgtPath),
                outputMbtiles = dir.resolve("terrain-rgb.mbtiles"),
                tileRoot = dir.resolve("terrain"),
                tileJson = dir.resolve("terrain").resolve("tiles.json"),
                styleJson = dir.resolve("style.json"),
                tilesUrl = "http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png",
                minZoom = 8,
                maxZoom = 8,
                name = "test-terrain",
            )
        )

        assertTrue(result.tileCount > 0)
        assertTrue(Files.exists(dir.resolve("terrain").resolve("tiles.json")))
        assertTrue(Files.exists(dir.resolve("style.json")))
        val tileCount = sqliteTileCount(result.outputMbtiles)
        assertEquals(result.tileCount, tileCount)
    }

    private fun testMbtilesWriterStoresMetadataAndTile() {
        val dir = Files.createTempDirectory("terrain-core-mbtiles-")
        val mbtilesPath = dir.resolve("terrain.mbtiles")
        MbtilesWriter(mbtilesPath).use { writer ->
            writer.writeMetadata(mapOf("format" to "png", "name" to "terrain"))
            writer.writeTile(2, 1, 2, "png-bytes".toByteArray())
        }
        val metadata = sqliteMetadataRows(mbtilesPath)
        val rows = sqliteTileRows(mbtilesPath)
        assertEquals(listOf("format" to "png", "name" to "terrain"), metadata)
        assertEquals(listOf(listOf(2, 1, 1, "png-bytes")), rows)
    }
}

fun main() {
    TerrainCoreTests.runAll()
    println("Terrain core tests passed")
}
