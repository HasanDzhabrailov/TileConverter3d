package com.terrainconverter.web

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Base64
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class BackendParityFixtureTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun backendHttpSnapshotsMatchLockedFixtures() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-parity-")
        application {
            terrainWebModule(
                fixtureDependencies(tempDir).copy(
                    conversionRunner = { request ->
                        val tile = request.paths.terrainRoot.resolve("8/0/0.png")
                        Files.createDirectories(tile.parent)
                        tile.writeBytes(PNG_BYTES)
                        request.paths.terrainMbtiles.writeBytes("mock-mbtiles".toByteArray())
                        request.paths.tilejson.writeText("{\"ok\": true}\n")
                        request.paths.stylejson.writeText("{\"version\": 8}\n")
                        request.log("conversion finished")
                        ConversionOutcome(BBox(10.0, 20.0, 11.0, 21.0), 1)
                    }
                )
            )
        }

        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        assertJsonEquals(readJson("contracts/backend/http/responses/health.json"), parse(client.get("/api/health").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/http/responses/jobs-empty.json"), parse(client.get("/api/jobs").bodyAsText()))

        val invalid = client.post("/api/jobs") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("minzoom", "bad")
                        append("hgt_files", "fake-hgt".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=N20E010.hgt")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        })
                    }
                )
            )
        }
        assertEquals(422, invalid.status.value)
        assertJsonEquals(readJson("contracts/backend/http/responses/create-job-invalid-minzoom.json"), parse(invalid.bodyAsText()))

        client.post("/api/jobs") { setBody(multipartHgtOnly()) }
        val completed = waitForStatus(client, "job-fixed", "completed")
        assertJsonEquals(readJson("contracts/backend/http/responses/job-completed.json"), completed)
    }

    @Test
    fun websocketTranscriptMatchesLockedFixture() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-ws-parity-")
        val startGate = CompletableDeferred<Unit>()
        application {
            terrainWebModule(
                fixtureDependencies(tempDir).copy(
                    launcher = { scope, block -> scope.launch { startGate.await(); block() } },
                    conversionRunner = { request ->
                        val tile = request.paths.terrainRoot.resolve("8/0/0.png")
                        Files.createDirectories(tile.parent)
                        tile.writeBytes(PNG_BYTES)
                        request.paths.terrainMbtiles.writeBytes("mock-mbtiles".toByteArray())
                        request.paths.tilejson.writeText("{\"ok\": true}\n")
                        request.paths.stylejson.writeText("{\"version\": 8}\n")
                        request.log("conversion finished")
                        delay(50)
                        ConversionOutcome(BBox(10.0, 20.0, 11.0, 21.0), 1)
                    }
                )
            )
        }

        val client = createClient {
            defaultRequest { header(HttpHeaders.Host, "testserver") }
            install(WebSockets)
        }

        val create = client.post("/api/jobs") { setBody(multipartHgtOnly()) }
        assertEquals("job-fixed", parse(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)

        val actualEvents = mutableListOf<JsonElement>()
        client.webSocket("/ws/jobs/job-fixed") {
            actualEvents += parse((incoming.receive() as Frame.Text).readText())
            startGate.complete(Unit)
            repeat(3) {
                actualEvents += parse((incoming.receive() as Frame.Text).readText())
            }
        }

        assertJsonEquals(readJson("contracts/backend/websocket/transcripts/job-success.json"), buildJsonArray { actualEvents.forEach { add(it) } })
    }

    @Test
    fun websocketFailureTranscriptMatchesLockedFixture() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-ws-failure-parity-")
        val startGate = CompletableDeferred<Unit>()
        application {
            terrainWebModule(
                fixtureDependencies(tempDir, jobId = "job-fixed-fail").copy(
                    launcher = { scope, block -> scope.launch { startGate.await(); block() } },
                    conversionRunner = {
                        delay(50)
                        throw RuntimeException("boom")
                    }
                )
            )
        }

        val client = createClient {
            defaultRequest { header(HttpHeaders.Host, "testserver") }
            install(WebSockets)
        }

        val create = client.post("/api/jobs") { setBody(multipartHgtOnly()) }
        assertEquals("job-fixed-fail", parse(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)

        val actualEvents = mutableListOf<JsonElement>()
        client.webSocket("/ws/jobs/job-fixed-fail") {
            actualEvents += parse((incoming.receive() as Frame.Text).readText())
            startGate.complete(Unit)
            repeat(3) {
                actualEvents += parse((incoming.receive() as Frame.Text).readText())
            }
        }

        assertJsonEquals(readJson("contracts/backend/websocket/transcripts/job-failure.json"), buildJsonArray { actualEvents.forEach { add(it) } })
    }

    @Test
    fun uploadedMbtilesSnapshotsMatchLockedFixtures() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-mbtiles-parity-")
        application { terrainWebModule(fixtureDependencies(tempDir)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }

        val response = client.post("/api/mbtiles") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("source_type", "raster")
                        append("mbtiles", buildMbtilesBytes(tempDir), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=sample.mbtiles")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        })
                    }
                )
            )
        }
        assertJsonEquals(readJson("contracts/backend/mbtiles/upload-response.json"), parse(response.bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/metadata.json"), parse(client.get("/api/mbtiles/tileset-fixed/metadata").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/tilejson.json"), parse(client.get("/api/mbtiles/tileset-fixed/tilejson").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/style.json"), parse(client.get("/api/mbtiles/tileset-fixed/style").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/style-mobile.json"), parse(client.get("/api/mbtiles/tileset-fixed/style-mobile").bodyAsText()))

        val tileBytes = client.get("/api/mbtiles/tileset-fixed/0/0/0.png").readBytes()
        assertEquals(readText("contracts/backend/mbtiles/tile-base64.txt").trim(), Base64.getEncoder().encodeToString(tileBytes))
    }

    @Test
    fun uploadedRasterDemMbtilesSnapshotsMatchLockedFixtures() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-mbtiles-dem-parity-")
        application { terrainWebModule(fixtureDependencies(tempDir, tilesetId = "tileset-fixed-dem")) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }

        val response = client.post("/api/mbtiles") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("source_type", "auto")
                        append("mbtiles", buildRasterDemMbtilesBytes(tempDir), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=terrain-rgb.mbtiles")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        })
                    }
                )
            )
        }

        assertJsonEquals(readJson("contracts/backend/mbtiles/upload-response-raster-dem.json"), parse(response.bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/tilejson-raster-dem.json"), parse(client.get("/api/mbtiles/tileset-fixed-dem/tilejson").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/style-raster-dem.json"), parse(client.get("/api/mbtiles/tileset-fixed-dem/style").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/style-mobile-raster-dem.json"), parse(client.get("/api/mbtiles/tileset-fixed-dem/style-mobile").bodyAsText()))
    }

    @Test
    fun uploadedMalformedMbtilesSnapshotsMatchLockedFixtures() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-mbtiles-malformed-parity-")
        application { terrainWebModule(fixtureDependencies(tempDir, tilesetId = "tileset-fixed-malformed")) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }

        val response = client.post("/api/mbtiles") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("source_type", "raster")
                        append("mbtiles", buildMalformedMbtilesBytes(tempDir), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=broken.mbtiles")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        })
                    }
                )
            )
        }

        assertJsonEquals(readJson("contracts/backend/mbtiles/upload-response-malformed.json"), parse(response.bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/metadata-malformed.json"), parse(client.get("/api/mbtiles/tileset-fixed-malformed/metadata").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/tilejson-malformed.json"), parse(client.get("/api/mbtiles/tileset-fixed-malformed/tilejson").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/style-malformed.json"), parse(client.get("/api/mbtiles/tileset-fixed-malformed/style").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/style-mobile-malformed.json"), parse(client.get("/api/mbtiles/tileset-fixed-malformed/style-mobile").bodyAsText()))
    }

    @Test
    fun uploadedMissingMetadataMbtilesSnapshotsMatchLockedFixtures() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-mbtiles-missing-parity-")
        application { terrainWebModule(fixtureDependencies(tempDir, tilesetId = "tileset-fixed-missing")) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }

        val response = client.post("/api/mbtiles") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("source_type", "raster")
                        append("mbtiles", buildMissingMetadataMbtilesBytes(tempDir), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=missing.mbtiles")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        })
                    }
                )
            )
        }

        assertJsonEquals(readJson("contracts/backend/mbtiles/upload-response-missing.json"), parse(response.bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/metadata-missing.json"), parse(client.get("/api/mbtiles/tileset-fixed-missing/metadata").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/tilejson-missing.json"), parse(client.get("/api/mbtiles/tileset-fixed-missing/tilejson").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/style-missing.json"), parse(client.get("/api/mbtiles/tileset-fixed-missing/style").bodyAsText()))
        assertJsonEquals(readJson("contracts/backend/mbtiles/style-mobile-missing.json"), parse(client.get("/api/mbtiles/tileset-fixed-missing/style-mobile").bodyAsText()))
    }

    private fun fixtureDependencies(tempDir: Path, jobId: String = "job-fixed", tilesetId: String = "tileset-fixed") = AppDependencies(
        settings = Settings(storageRoot = tempDir.resolve("data"), frontendDist = tempDir.resolve("dist")),
        now = { "2026-04-28T00:00:00Z" },
        jobIdProvider = { jobId },
        tilesetIdProvider = { tilesetId },
    )

    private fun multipartHgtOnly() = MultiPartFormDataContent(
        formData {
            append("hgt_files", "fake-hgt".toByteArray(), Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=N20E010.hgt")
                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
            })
        }
    )

    private suspend fun waitForStatus(
        client: io.ktor.client.HttpClient,
        jobId: String,
        expectedStatus: String,
        attempts: Int = 60,
    ): JsonElement {
        repeat(attempts) {
            val payload = parse(client.get("/api/jobs/$jobId").bodyAsText())
            if (payload.jsonObject["status"]!!.jsonPrimitive.content == expectedStatus) {
                return payload
            }
            Thread.sleep(50)
        }
        error("job $jobId did not reach status $expectedStatus")
    }

    private fun buildMbtilesBytes(tempDir: Path): ByteArray {
        Class.forName("org.sqlite.JDBC")
        val db = tempDir.resolve("sample.mbtiles")
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
                statement.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('format', 'png')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('name', 'Sample Tileset')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('attribution', 'Sample attribution')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('bounds', '10,20,11,21')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('center', '10.5,20.5,4')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('minzoom', '4')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('maxzoom', '12')")
            }
            connection.prepareStatement("INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)").use { statement ->
                statement.setInt(1, 0)
                statement.setInt(2, 0)
                statement.setInt(3, 0)
                statement.setBytes(4, PNG_BYTES)
                statement.executeUpdate()
            }
        }
        return db.readBytes()
    }

    private fun buildRasterDemMbtilesBytes(tempDir: Path): ByteArray {
        Class.forName("org.sqlite.JDBC")
        val db = tempDir.resolve("terrain-rgb.mbtiles")
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
                statement.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('format', 'png')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('name', 'Terrain RGB Demo')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('description', 'terrain dem source')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('attribution', 'Terrain attribution')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('bounds', '10,20,11,21')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('center', '10.5,20.5,6')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('minzoom', '6')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('maxzoom', '10')")
            }
            connection.prepareStatement("INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)").use { statement ->
                statement.setInt(1, 0)
                statement.setInt(2, 0)
                statement.setInt(3, 0)
                statement.setBytes(4, PNG_BYTES)
                statement.executeUpdate()
            }
        }
        return db.readBytes()
    }

    private fun buildMalformedMbtilesBytes(tempDir: Path): ByteArray {
        Class.forName("org.sqlite.JDBC")
        val db = tempDir.resolve("broken.mbtiles")
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
                statement.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('format', 'png')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('name', 'Broken Tileset')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('bounds', 'bad,bounds')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('center', 'oops')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('minzoom', 'abc')")
                statement.execute("INSERT INTO metadata (name, value) VALUES ('maxzoom', 'xyz')")
            }
            connection.prepareStatement("INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)").use { statement ->
                statement.setInt(1, 0)
                statement.setInt(2, 0)
                statement.setInt(3, 0)
                statement.setBytes(4, PNG_BYTES)
                statement.executeUpdate()
            }
        }
        return db.readBytes()
    }

    private fun buildMissingMetadataMbtilesBytes(tempDir: Path): ByteArray {
        Class.forName("org.sqlite.JDBC")
        val db = tempDir.resolve("missing.mbtiles")
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
                statement.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            }
            connection.prepareStatement("INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)").use { statement ->
                statement.setInt(1, 0)
                statement.setInt(2, 0)
                statement.setInt(3, 0)
                statement.setBytes(4, PNG_BYTES)
                statement.executeUpdate()
            }
        }
        return db.readBytes()
    }

    private fun assertJsonEquals(expected: JsonElement, actual: JsonElement) {
        assertEquals(expected, actual, "Expected fixture parity at JSON boundary")
    }

    private fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    private fun readJson(path: String): JsonElement = parse(readText(path))

    private fun readText(path: String): String = Files.readString(fixtureRoot().resolve(path))

    private fun fixtureRoot(): Path = repoRoot().resolve("kotlin/parity-fixtures")

    private fun repoRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.exists(it.resolve("kotlin/parity-fixtures/manifest.json")) }
        ?: error("Could not locate repo root from ${Path.of("").toAbsolutePath()}")

    companion object {
        private val PNG_BYTES = "\u0089PNG\r\n\u001a\nmock".toByteArray(Charsets.ISO_8859_1)
    }
}
