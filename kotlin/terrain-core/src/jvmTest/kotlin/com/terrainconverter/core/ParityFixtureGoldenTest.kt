package com.terrainconverter.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ParityFixtureGoldenTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun hgtFilenameParsingMatchesLockedFixture() {
        val inputCases = readJsonArray("inputs/hgt/filenames/cases.json")
        val actual = buildJsonArray {
            inputCases.forEach { entry ->
                val case = entry.jsonObject
                val filename = case["filename"]!!.jsonPrimitive.content
                val error = case["error"]?.jsonPrimitive?.content
                if (error != null) {
                    val thrown = kotlin.runCatching { parseHgtCoordinate(Path.of(filename)) }.exceptionOrNull()
                    add(
                        buildJsonObject {
                            put("filename", filename)
                            put("error", assertNotNull(thrown).message ?: "")
                        }
                    )
                } else {
                    val coordinate = parseHgtCoordinate(Path.of(filename))
                    add(
                        buildJsonObject {
                            put("filename", filename)
                            put("lat", coordinate.lat)
                            put("lon", coordinate.lon)
                        }
                    )
                }
            }
        }

        assertJsonEquals(readJson("contracts/core/hgt-filename-parse.json"), actual)
    }

    @Test
    fun gridValidationMatchesLockedFixture() {
        val fixture = readJsonObject("inputs/hgt/grid-validation/cases.json")
        val tempDir = Files.createTempDirectory("terrain-core-grid-fixtures-")
        val supported = fixture["supported_sizes"]!!.jsonArray.map { it.jsonPrimitive.int }
        supported.forEachIndexed { index, size ->
            val path = tempDir.resolve("N00E00$index.hgt")
            writeBlankHgt(path, size)
            assertEquals(size, inferHgtSize(path))
        }

        val invalidPath = tempDir.resolve("N00E010.hgt")
        Files.write(invalidPath, ByteArray(fixture["invalid_size_bytes"]!!.jsonPrimitive.int))
        val invalidError = kotlin.runCatching { inferHgtSize(invalidPath) }.exceptionOrNull()?.message
        assertEquals(fixture["invalid_size_error"]!!.jsonPrimitive.content, invalidError)

        val mixedDir = Files.createTempDirectory(tempDir, "mixed-")
        val tile1201 = mixedDir.resolve("N01E000.hgt")
        val tile3601 = mixedDir.resolve("N01E001.hgt")
        writeBlankHgt(tile1201, 1201)
        writeBlankHgt(tile3601, 3601)
        val mixedError = kotlin.runCatching { validateInputs(listOf(mixedDir)) }.exceptionOrNull()?.message
        assertEquals(fixture["mixed_resolution_error"]!!.jsonPrimitive.content, mixedError)

        assertJsonEquals(readJson("contracts/core/grid-validation.json"), fixture)
    }

    @Test
    fun samplingAndVoidHandlingMatchLockedFixture() {
        val inputs = readJsonObject("inputs/hgt/sampling/probes.json")
        val expected = readJsonObject("contracts/core/sample-probes.json")

        val bilinearTile = writeFixtureTile(inputs["bilinear"]!!.jsonObject)
        val bilinearCase = inputs["bilinear"]!!.jsonObject
        val bilinearActual = buildJsonArray {
            bilinearCase["probes"]!!.jsonArray.forEach { probe ->
                val entry = probe.jsonObject
                val value = readHgt(bilinearTile).sampleBilinear(
                    entry["lon"]!!.jsonPrimitive.double,
                    entry["lat"]!!.jsonPrimitive.double,
                )
                add(JsonPrimitive(assertNotNull(value)))
            }
        }
        assertDoubleArrayEquals(expected["bilinear_expected"]!!.jsonArray, bilinearActual)

        val voidTile = writeFixtureTile(inputs["void_weighting"]!!.jsonObject)
        val voidCase = inputs["void_weighting"]!!.jsonObject
        val voidActual = buildJsonArray {
            voidCase["probes"]!!.jsonArray.forEach { probe ->
                val entry = probe.jsonObject
                val value = readHgt(voidTile).sampleBilinear(
                    entry["lon"]!!.jsonPrimitive.double,
                    entry["lat"]!!.jsonPrimitive.double,
                )
                add(JsonPrimitive(assertNotNull(value)))
            }
        }
        assertDoubleArrayEquals(expected["void_expected"]!!.jsonArray, voidActual)
    }

    @Test
    fun boundsAndTileCoverageMatchLockedFixture() {
        val inputCases = readJsonArray("inputs/hgt/coverage/cases.json")
        val actual = buildJsonArray {
            inputCases.forEach { caseEntry ->
                val descriptor = caseEntry.jsonObject
                val boundsArray = descriptor["bounds"]!!.jsonArray
                val bounds = Bounds(
                    boundsArray[0].jsonPrimitive.double,
                    boundsArray[1].jsonPrimitive.double,
                    boundsArray[2].jsonPrimitive.double,
                    boundsArray[3].jsonPrimitive.double,
                )
                val zoom = descriptor["zoom"]!!.jsonPrimitive.int
                val tiles = tilesForBounds(bounds, zoom)
                add(
                    buildJsonObject {
                        put("id", descriptor["id"]!!.jsonPrimitive.content)
                        put("tiles", buildJsonArray {
                            tiles.forEach { tile ->
                                add(buildJsonArray {
                                    add(JsonPrimitive(tile.first))
                                    add(JsonPrimitive(tile.second))
                                    add(JsonPrimitive(tile.third))
                                })
                            }
                        })
                        put("count", tiles.size)
                    }
                )
            }
        }
        assertJsonEquals(readJson("contracts/core/tile-coverage.json"), actual)
    }

    @Test
    fun terrainRgbAndPngGoldensMatchLockedFixtures() {
        val renderInput = readJsonObject("inputs/hgt/render/terrain-rgb-2x2.json")
        val elevations = renderInput["elevations"]!!.jsonArray.map { it.jsonPrimitive.double }
        val rgba = ByteArray(elevations.size * 4)
        elevations.forEachIndexed { index, elevation ->
            val encoded = encodeElevation(elevation)
            val offset = index * 4
            rgba[offset] = encoded.red.toByte()
            rgba[offset + 1] = encoded.green.toByte()
            rgba[offset + 2] = encoded.blue.toByte()
            rgba[offset + 3] = 0xFF.toByte()
        }
        assertJsonEquals(
            readJson("goldens/core/rgba/terrain-rgb-2x2.json"),
            buildJsonArray { rgba.forEach { add(JsonPrimitive(it.toUByte().toInt())) } },
        )

        val pngBytes = writePngRgba(2, 2, rgba)
        assertEquals(readText("goldens/core/png/terrain-rgb-2x2-base64.txt").trim(), Base64.getEncoder().encodeToString(pngBytes))

        val decodedRgba = decodePngRgba(pngBytes)
        assertJsonEquals(
            readJson("goldens/core/rgba/terrain-rgb-2x2.json"),
            buildJsonArray { decodedRgba.forEach { add(JsonPrimitive(it.toUByte().toInt())) } },
        )
    }

    @Test
    fun tilejsonAndStyleOutputsMatchLockedGoldens() {
        val bounds = Bounds(10.0, 20.0, 11.0, 21.0)
        val tileJson = buildTileJson(bounds, 8, 12, "http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png")
        assertEquals(readText("contracts/core/tilejson.json"), renderPrettyJson(tileJson))

        val style = buildStyle("http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png")
        assertEquals(readText("contracts/core/style.json"), renderPrettyJson(style))
    }

    @Test
    fun mbtilesMetadataAndRowsMatchLockedGoldens() {
        val tempDir = Files.createTempDirectory("terrain-core-mbtiles-golden-")
        val mbtilesPath = tempDir.resolve("terrain.mbtiles")
        MbtilesWriter(mbtilesPath).use { writer ->
            writer.writeMetadata(mapOf("format" to "png", "name" to "terrain"))
            writer.writeTile(2, 1, 2, "png-bytes".toByteArray())
        }

        assertJsonEquals(readJson("goldens/core/mbtiles/metadata.json"), metadataJson(mbtilesPath))
        assertJsonEquals(readJson("goldens/core/mbtiles/tiles.json"), tileRowsJson(mbtilesPath))
    }

    private fun metadataJson(dbPath: Path): JsonArray {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name, value FROM metadata ORDER BY name").use { rows ->
                    return buildJsonArray {
                        while (rows.next()) {
                            add(buildJsonArray {
                                add(JsonPrimitive(rows.getString(1)))
                                add(JsonPrimitive(rows.getString(2)))
                            })
                        }
                    }
                }
            }
        }
    }

    private fun tileRowsJson(dbPath: Path): JsonArray {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles ORDER BY zoom_level, tile_column, tile_row").use { rows ->
                    return buildJsonArray {
                        while (rows.next()) {
                            add(buildJsonArray {
                                add(JsonPrimitive(rows.getInt(1)))
                                add(JsonPrimitive(rows.getInt(2)))
                                add(JsonPrimitive(rows.getInt(3)))
                                add(JsonPrimitive(rows.getBytes(4).decodeToString()))
                            })
                        }
                    }
                }
            }
        }
    }

    private fun writeFixtureTile(descriptor: JsonObject): Path {
        val tempDir = Files.createTempDirectory("terrain-core-sampling-fixture-")
        val path = tempDir.resolve(descriptor["filename"]!!.jsonPrimitive.content)
        val size = descriptor["size"]!!.jsonPrimitive.int
        val updates = descriptor["updates"]!!.jsonArray.map {
            val entry = it.jsonObject
            Triple(entry["row"]!!.jsonPrimitive.int, entry["col"]!!.jsonPrimitive.int, entry["value"]!!.jsonPrimitive.int)
        }
        writeConfiguredHgt(path, size, updates)
        return path
    }

    private fun writeConfiguredHgt(path: Path, size: Int, updates: List<Triple<Int, Int, Int>>) {
        val samples = ShortArray(size * size)
        updates.forEach { (row, col, value) ->
            samples[(row * size) + col] = value.toShort()
        }
        val bytes = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        samples.forEach(buffer::putShort)
        Files.write(path, bytes)
    }

    private fun writeBlankHgt(path: Path, size: Int) {
        val bytesPerRow = size * 2
        val rowBytes = ByteArray(bytesPerRow)
        Files.newOutputStream(path).use { output ->
            repeat(size) { output.write(rowBytes) }
        }
    }

    private fun decodePngRgba(pngBytes: ByteArray): ByteArray {
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
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            raw.write(buffer, 0, count)
        }
        inflater.end()
        val rawBytes = raw.toByteArray()
        val stride = (width * 4) + 1
        val rgba = ByteArray(width * height * 4)
        for (row in 0 until height) {
            val rowOffset = row * stride
            System.arraycopy(rawBytes, rowOffset + 1, rgba, row * width * 4, width * 4)
        }
        return rgba
    }

    private fun assertJsonEquals(expected: JsonElement, actual: JsonElement) {
        assertEquals(expected, actual, "Expected fixture parity at JSON boundary")
    }

    private fun assertDoubleArrayEquals(expected: JsonArray, actual: JsonArray, tolerance: Double = 1e-5) {
        assertEquals(expected.size, actual.size, "Expected numeric fixture arrays of same size")
        expected.indices.forEach { index ->
            val expectedValue = expected[index].jsonPrimitive.double
            val actualValue = actual[index].jsonPrimitive.double
            assertEquals(true, abs(expectedValue - actualValue) <= tolerance, "Expected <$expectedValue> but was <$actualValue> within $tolerance at index $index")
        }
    }

    private fun readJson(path: String): JsonElement = json.parseToJsonElement(readText(path))

    private fun readJsonArray(path: String): JsonArray = readJson(path).jsonArray

    private fun readJsonObject(path: String): JsonObject = readJson(path).jsonObject

    private fun readText(path: String): String = Files.readString(fixtureRoot().resolve(path))

    private fun fixtureRoot(): Path = repoRoot().resolve("kotlin/parity-fixtures")

    private fun repoRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.exists(it.resolve("kotlin/parity-fixtures/manifest.json")) }
        ?: error("Could not locate repo root from ${Path.of("").toAbsolutePath()}")
}
