package com.terrainconverter.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals

class CliParityFixtureTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun cliDefaultsMatchLockedFixture() {
        val args = parseCliArgs(listOf("input.hgt"))
        val actual = buildJsonObject {
            put("inputs", buildJsonArray { args.inputs.forEach { add(JsonPrimitive(it)) } })
            put("outputMbtiles", args.outputMbtiles)
            put("tileRoot", args.tileRoot)
            put("tileJson", args.tileJson)
            put("styleJson", args.styleJson)
            put("tilesUrl", args.tilesUrl)
            put("minZoom", args.minZoom)
            put("maxZoom", args.maxZoom)
            put("tileSize", args.tileSize)
            put("scheme", args.scheme)
            put("encoding", args.encoding)
            put("name", args.name)
        }
        assertJsonEquals(readJson("contracts/cli/defaults.json"), actual)
    }

    @Test
    fun cliOutputLayoutMatchesLockedFixture() {
        val inputDir = Files.createTempDirectory("terrain-cli-parity-input-")
        val input = inputDir.resolve("N00E000.hgt")
        writeBlankHgt(input, 1201)

        val outputDir = Files.createTempDirectory("terrain-cli-parity-output-")
        main(
            arrayOf(
                input.toString(),
                "--output-mbtiles", outputDir.resolve("terrain-rgb.mbtiles").toString(),
                "--tile-root", outputDir.resolve("terrain").toString(),
                "--tilejson", outputDir.resolve("terrain/tiles.json").toString(),
                "--style-json", outputDir.resolve("style.json").toString(),
                "--minzoom", "0",
                "--maxzoom", "0",
            )
        )

        val files = Files.walk(outputDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { outputDir.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }
        assertJsonEquals(
            readJson("goldens/cli/layout/files.json"),
            buildJsonArray { files.forEach { add(JsonPrimitive(it)) } },
        )

        val tilejson = json.parseToJsonElement(outputDir.resolve("terrain/tiles.json").readText()).jsonObject
        assertEquals("xyz", tilejson["scheme"]!!.jsonPrimitive.content)
        assertEquals(0, tilejson["minzoom"]!!.jsonPrimitive.int)
        assertEquals(0, tilejson["maxzoom"]!!.jsonPrimitive.int)
    }

    private fun writeBlankHgt(path: Path, size: Int) {
        val samples = ShortArray(size * size)
        val bytes = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        samples.forEach(buffer::putShort)
        Files.write(path, bytes)
    }

    private fun assertJsonEquals(expected: JsonElement, actual: JsonElement) {
        assertEquals(expected, actual, "Expected fixture parity at JSON boundary")
    }

    private fun readJson(path: String): JsonElement = json.parseToJsonElement(Files.readString(fixtureRoot().resolve(path)))

    private fun fixtureRoot(): Path = repoRoot().resolve("kotlin/parity-fixtures")

    private fun repoRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.exists(it.resolve("kotlin/parity-fixtures/manifest.json")) }
        ?: error("Could not locate repo root from ${Path.of("").toAbsolutePath()}")
}
