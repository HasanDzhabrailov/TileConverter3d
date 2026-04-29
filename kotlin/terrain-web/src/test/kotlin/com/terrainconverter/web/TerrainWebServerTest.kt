package com.terrainconverter.web

import io.ktor.client.plugins.defaultRequest
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
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainWebServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun healthEndpoint() = testApplication {
        application { terrainWebModule(testDependencies()) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val response = client.get("/api/health")
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }

    @Test
    fun jobsListStartsEmpty() = testApplication {
        application { terrainWebModule(testDependencies()) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        assertEquals("[]", client.get("/api/jobs").bodyAsText())
    }

    @Test
    fun serverInfoReturnsMobileAndLocalhost() = testApplication {
        application { terrainWebModule(testDependencies()) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val payload = json.parseToJsonElement(client.get("/api/server-info").bodyAsText()).jsonObject
        val addresses = payload["addresses"]!!.jsonArray
        assertEquals("mobile", addresses[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("localhost", addresses[1].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun createJobSavesUploadsAndManualBbox() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application {
            terrainWebModule(
                testDependencies(tempDir).copy(
                    launcher = { _, _ -> },
                )
            )
        }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val response = client.post("/api/jobs") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("bbox_mode", "manual")
                        append("west", "10")
                        append("south", "20")
                        append("east", "11")
                        append("north", "21")
                        append("minzoom", "9")
                        append("maxzoom", "10")
                        append("tile_size", "512")
                        append("scheme", "tms")
                        append("encoding", "mapbox")
                        append("hgt_files", "fake-hgt".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=N20E010.hgt")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        })
                        append("base_mbtiles", "fake-mbtiles".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=base.mbtiles")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        })
                    }
                )
            )
        }
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val jobId = payload["id"]!!.jsonPrimitive.content
        val uploads = tempDir.resolve("data/jobs/$jobId/uploads")
        assertEquals("pending", payload["status"]!!.jsonPrimitive.content)
        assertTrue(payload["has_base_mbtiles"]!!.jsonPrimitive.boolean)
        assertEquals("fake-hgt", uploads.resolve("N20E010.hgt").readText())
        assertEquals("fake-mbtiles", uploads.resolve("base.mbtiles").readText())
    }

    @Test
    fun createJobRejectsInvalidMinzoom() = testApplication {
        application {
            terrainWebModule(
                testDependencies().copy(
                    launcher = { _, _ -> },
                )
            )
        }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val response = client.post("/api/jobs") {
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
        assertEquals(422, response.status.value)
        assertTrue(response.bodyAsText().contains("minzoom must be an integer"))
    }

    @Test
    fun jobCompletesAndServesArtifacts() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application {
            terrainWebModule(
                testDependencies(tempDir).copy(
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
        val createResponse = client.post("/api/jobs") {
            setBody(multipartHgtOnly())
        }
        val jobId = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val job = waitForStatus(client, jobId, "completed")
        val artifacts = job["artifacts"]!!.jsonObject
        assertEquals(1, job["result"]!!.jsonObject["tile_count"]!!.jsonPrimitive.int)
        assertEquals("http://testserver/api/jobs/$jobId/style", artifacts["public_stylejson"]!!.jsonPrimitive.content)
        assertEquals(200, client.get("/api/jobs/$jobId/downloads/terrain-rgb.mbtiles").status.value)
        assertEquals(200, client.get("/api/jobs/$jobId/tilejson").status.value)
        assertEquals(200, client.get("/api/jobs/$jobId/style").status.value)
        val tileResponse = client.get("/api/jobs/$jobId/terrain/8/0/0.png")
        assertEquals(200, tileResponse.status.value)
        assertEquals(ContentType.Image.PNG.toString(), tileResponse.contentType().toString())
    }

    @Test
    fun jobFailureUpdatesStatusAndLogs() = testApplication {
        application {
            terrainWebModule(
                testDependencies().copy(
                    conversionRunner = { throw RuntimeException("boom") }
                )
            )
        }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val createResponse = client.post("/api/jobs") { setBody(multipartHgtOnly()) }
        val jobId = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val job = waitForStatus(client, jobId, "failed")
        val logs = json.parseToJsonElement(client.get("/api/jobs/$jobId/logs").bodyAsText()).jsonObject["logs"]!!.jsonArray
        assertEquals("boom", job["error"]!!.jsonPrimitive.content)
        assertTrue(logs.any { "ERROR: boom" in it.jsonPrimitive.content })
    }

    @Test
    fun uploadMbtilesAndServeTile() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application { terrainWebModule(testDependencies(tempDir)) }
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
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tilesetId = payload["id"]!!.jsonPrimitive.content
        assertEquals("sample.mbtiles", payload["filename"]!!.jsonPrimitive.content)
        assertEquals("raster", payload["source_type"]!!.jsonPrimitive.content)
        assertEquals("Sample Tileset", payload["name"]!!.jsonPrimitive.content)
        assertEquals(200, client.get("/api/mbtiles/$tilesetId/tilejson").status.value)
        assertEquals(200, client.get("/api/mbtiles/$tilesetId/style").status.value)
        assertEquals(200, client.get("/api/mbtiles/$tilesetId/style-mobile").status.value)
        val tileResponse = client.get("/api/mbtiles/$tilesetId/0/0/0.png")
        assertEquals(200, tileResponse.status.value)
        assertTrue(tileResponse.readBytes().contentEquals(PNG_BYTES))
    }

    @Test
    fun mbtilesTileRejectsInvalidPathIntegers() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application { terrainWebModule(testDependencies(tempDir)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val uploadResponse = client.post("/api/mbtiles") {
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
        val tilesetId = json.parseToJsonElement(uploadResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val response = client.get("/api/mbtiles/$tilesetId/not-an-int/0/0")
        assertEquals(422, response.status.value)
    }

    @Test
    fun writeJobDocumentsUsesRelativeUrls() {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        val tilejsonPath = tempDir.resolve("tiles.json")
        val stylejsonPath = tempDir.resolve("style.json")
        assertDoesNotThrow {
            writeJobDocuments(
                jobId = "job123",
                options = JobOptions(),
                bounds = BBox(10.0, 20.0, 11.0, 21.0),
                tilejsonPath = tilejsonPath,
                stylejsonPath = stylejsonPath,
                hasBaseMbtiles = true,
            )
        }
        assertTrue(tilejsonPath.readText().contains("\"/api/jobs/job123/terrain/{z}/{x}/{y}.png\""))
        assertTrue(stylejsonPath.readText().contains("\"/api/jobs/job123/terrain/{z}/{x}/{y}.png\""))
        assertTrue(stylejsonPath.readText().contains("\"/api/jobs/job123/base/{z}/{x}/{y}\""))
    }

    private fun multipartHgtOnly() = MultiPartFormDataContent(
        formData {
            append("hgt_files", "fake-hgt".toByteArray(), Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=N20E010.hgt")
                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
            })
        }
    )

    private fun testDependencies(tempDir: Path = Files.createTempDirectory("terrain-web-test-")) = AppDependencies(
        settings = Settings(storageRoot = tempDir.resolve("data"), frontendDist = tempDir.resolve("dist")),
        now = { "2026-04-28T00:00:00Z" },
    )

    private suspend fun waitForStatus(
        client: io.ktor.client.HttpClient,
        jobId: String,
        expectedStatus: String,
        attempts: Int = 60,
    ): kotlinx.serialization.json.JsonObject {
        repeat(attempts) {
            val payload = json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
            if (payload["status"]!!.jsonPrimitive.content == expectedStatus) {
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

    companion object {
        private val PNG_BYTES = "\u0089PNG\r\n\u001a\nmock".toByteArray(Charsets.ISO_8859_1)
    }
}
