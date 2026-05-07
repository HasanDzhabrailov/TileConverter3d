package com.terrainconverter.web

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun serverInfoReturnsLanAndLocalhost() = testApplication {
        application { terrainWebModule(testDependencies()) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val payload = json.parseToJsonElement(client.get("/api/server-info").bodyAsText()).jsonObject
        val addresses = payload["addresses"]!!.jsonArray
        assertTrue(addresses.any { it.jsonObject["id"]!!.jsonPrimitive.content == "localhost" })
        addresses.firstOrNull { it.jsonObject["id"]!!.jsonPrimitive.content == "lan-primary" }?.let { lan ->
            assertEquals("Wi-Fi / LAN", lan.jsonObject["label"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun baseSourcesSeedBuiltinsAndPersistCustomSources() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application { terrainWebModule(testDependencies(tempDir)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }

        val initial = json.parseToJsonElement(client.get("/api/base-sources").bodyAsText()).jsonArray
        assertTrue(initial.any { it.jsonObject["id"]!!.jsonPrimitive.content == "openstreetmap" })
        assertTrue(initial.any { it.jsonObject["id"]!!.jsonPrimitive.content == "none" })

        val createResponse = client.post("/api/base-sources") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                """
                {"name":"Local Tiles","url_template":"/tiles/{z}/{x}/{y}.png","attribution":"Local data","max_zoom":18}
                """.trimIndent()
            )
        }

        assertEquals(201, createResponse.status.value)
        val created = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        assertEquals("local-tiles", created["id"]!!.jsonPrimitive.content)
        assertFalse(created["is_builtin"]!!.jsonPrimitive.boolean)

        val persisted = BaseMapSourceRepository(tempDir.resolve("data/app.sqlite")) { "2026-04-28T00:00:01Z" }.list()
        assertTrue(persisted.any { it.id == "local-tiles" })
    }

    @Test
    fun baseSourcesSupportCustomUpdateAndDelete() = testApplication {
        application { terrainWebModule(testDependencies()) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val createResponse = client.post("/api/base-sources") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"name":"Custom","url_template":"https://example.com/{z}/{x}/{y}.png","max_zoom":12}""")
        }
        val sourceId = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updateResponse = client.put("/api/base-sources/$sourceId") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"name":"Custom Updated","url_template":"https://cdn.example.com/{z}/{x}/{y}.jpg","attribution":"Updated","max_zoom":20}""")
        }
        val updated = json.parseToJsonElement(updateResponse.bodyAsText()).jsonObject
        assertEquals(200, updateResponse.status.value)
        assertEquals("Custom Updated", updated["name"]!!.jsonPrimitive.content)
        assertEquals(20, updated["max_zoom"]!!.jsonPrimitive.int)

        val deleteResponse = client.delete("/api/base-sources/$sourceId")
        assertEquals(204, deleteResponse.status.value)
        val afterDelete = json.parseToJsonElement(client.get("/api/base-sources").bodyAsText()).jsonArray
        assertFalse(afterDelete.any { it.jsonObject["id"]!!.jsonPrimitive.content == sourceId })
    }

    @Test
    fun baseSourcesRejectInvalidCustomSourceAndBuiltinMutation() = testApplication {
        application { terrainWebModule(testDependencies()) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }

        val invalid = client.post("/api/base-sources") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"name":"Bad","url_template":"ftp://example.com/{z}/{x}/{y}.png","max_zoom":23}""")
        }
        assertEquals(422, invalid.status.value)

        val updateBuiltin = client.put("/api/base-sources/openstreetmap") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"name":"OSM","url_template":"https://example.com/{z}/{x}/{y}.png","max_zoom":19}""")
        }
        assertEquals(409, updateBuiltin.status.value)

        val deleteBuiltin = client.delete("/api/base-sources/openstreetmap")
        assertEquals(409, deleteBuiltin.status.value)
    }

    @Test
    fun baseSourcesReturnErrorPayloadForMalformedRequestsAndUnknownSources() = testApplication {
        application { terrainWebModule(testDependencies()) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }

        val malformed = client.post("/api/base-sources") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{" )
        }
        val malformedPayload = json.parseToJsonElement(malformed.bodyAsText()).jsonObject
        assertEquals(400, malformed.status.value)
        assertTrue(malformedPayload["detail"]!!.jsonPrimitive.content.isNotBlank())

        val missingFields = client.post("/api/base-sources") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"name":"Missing fields"}""")
        }
        val missingPayload = json.parseToJsonElement(missingFields.bodyAsText()).jsonObject
        assertEquals(400, missingFields.status.value)
        assertTrue(missingPayload["detail"]!!.jsonPrimitive.content.isNotBlank())

        val updateMissing = client.put("/api/base-sources/missing-source") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"name":"Custom","url_template":"https://example.com/{z}/{x}/{y}.png","max_zoom":12}""")
        }
        assertEquals(404, updateMissing.status.value)
        assertTrue(json.parseToJsonElement(updateMissing.bodyAsText()).jsonObject["detail"]!!.jsonPrimitive.content.contains("not found"))

        val deleteMissing = client.delete("/api/base-sources/missing-source")
        assertEquals(404, deleteMissing.status.value)
        assertTrue(json.parseToJsonElement(deleteMissing.bodyAsText()).jsonObject["detail"]!!.jsonPrimitive.content.contains("not found"))
    }

    @Test
    fun baseSourceValidationCoversRequiredFieldsTokensAndMaxZoomBounds() = testApplication {
        application { terrainWebModule(testDependencies()) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }

        val invalidBodies = listOf(
            """{"name":"   ","url_template":"https://example.com/{z}/{x}/{y}.png","max_zoom":12}""",
            """{"name":"Missing z","url_template":"https://example.com/{x}/{y}.png","max_zoom":12}""",
            """{"name":"Missing x","url_template":"https://example.com/{z}/{y}.png","max_zoom":12}""",
            """{"name":"Missing y","url_template":"https://example.com/{z}/{x}.png","max_zoom":12}""",
            """{"name":"Bad scheme","url_template":"ftp://example.com/{z}/{x}/{y}.png","max_zoom":12}""",
            """{"name":"Too low","url_template":"https://example.com/{z}/{x}/{y}.png","max_zoom":0}""",
            """{"name":"Too high","url_template":"https://example.com/{z}/{x}/{y}.png","max_zoom":23}""",
        )

        invalidBodies.forEach { body ->
            val response = client.post("/api/base-sources") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body)
            }
            assertEquals(422, response.status.value, body)
            assertTrue(json.parseToJsonElement(response.bodyAsText()).jsonObject["detail"]!!.jsonPrimitive.content.isNotBlank())
        }

        listOf(1, 22).forEach { maxZoom ->
            val response = client.post("/api/base-sources") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"name":"Boundary $maxZoom","url_template":"https://example.com/{z}/{x}/{y}.png","max_zoom":$maxZoom}""")
            }
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(201, response.status.value)
            assertEquals(maxZoom, payload["max_zoom"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun baseSourceBuiltinSeedIsIdempotent() {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        val db = tempDir.resolve("data/app.sqlite")
        val first = BaseMapSourceRepository(db) { "2026-04-28T00:00:00Z" }.list()
        val second = BaseMapSourceRepository(db) { "2026-04-28T00:00:01Z" }.list()
        val builtinIds = listOf("openstreetmap", "opentopomap", "cartodb-positron", "cartodb-dark-matter", "esri-satellite", "google-satellite", "google-roadmap", "yandex-satellite", "yandex-map", "none")

        builtinIds.forEach { id ->
            assertEquals(1, second.count { it.id == id }, id)
            assertEquals(
                first.first { it.id == id }.updatedAt,
                second.first { it.id == id }.updatedAt,
                id,
            )
        }
    }

    @Test
    fun lanHostCandidateAcceptsPrivateNetworksWithoutDockerRangeHardcode() {
        assertTrue(isLanIpv4Host("192.168.1.20"))
        assertTrue(isLanIpv4Host("172.20.1.20"))
        assertFalse(isLanIpv4Host("127.0.0.1"))
    }

    @Test
    fun createJobSavesUploadsAndManualBbox() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application {
            terrainWebModule(
                testDependencies(tempDir).copy(
                    launcher = { _, _ -> SupervisorJob() },
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
                    launcher = { _, _ -> SupervisorJob() },
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
    fun systemCacheStatsAndClearSelectedData() = testApplication {
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
                        ConversionOutcome(BBox(10.0, 20.0, 11.0, 21.0), 1)
                    }
                )
            )
        }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val sourceResponse = client.post("/api/base-sources") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"name":"Custom","url_template":"https://example.com/{z}/{x}/{y}.png","max_zoom":12}""")
        }
        val sourceId = json.parseToJsonElement(sourceResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val jobId = json.parseToJsonElement(client.post("/api/jobs") { setBody(multipartHgtOnly()) }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        waitForStatus(client, jobId, "completed")
        val mbtilesResponse = client.post("/api/mbtiles") {
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
        val tilesetId = json.parseToJsonElement(mbtilesResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val stats = json.parseToJsonElement(client.get("/api/system/storage").bodyAsText()).jsonObject
        assertEquals(1, stats["completed_jobs"]!!.jsonPrimitive.int)
        assertEquals(1, stats["uploaded_tilesets"]!!.jsonPrimitive.int)
        assertEquals(1, stats["custom_sources"]!!.jsonPrimitive.int)

        val clearResponse = client.delete("/api/system/cache") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"completed_jobs":true,"uploaded_tilesets":true,"custom_sources":true}""")
        }
        val cleared = json.parseToJsonElement(clearResponse.bodyAsText()).jsonObject
        assertEquals(200, clearResponse.status.value)
        assertEquals(1, cleared["deleted_completed_jobs"]!!.jsonPrimitive.int)
        assertEquals(1, cleared["deleted_uploaded_tilesets"]!!.jsonPrimitive.int)
        assertEquals(1, cleared["deleted_custom_sources"]!!.jsonPrimitive.int)
        assertFalse(Files.exists(tempDir.resolve("data/jobs/$jobId")))
        assertFalse(Files.exists(tempDir.resolve("data/tilesets/$tilesetId")))
        assertEquals(404, client.get("/api/jobs/$jobId").status.value)
        assertEquals(404, client.get("/api/mbtiles/$tilesetId").status.value)
        val sources = json.parseToJsonElement(client.get("/api/base-sources").bodyAsText()).jsonArray
        assertFalse(sources.any { it.jsonObject["id"]!!.jsonPrimitive.content == sourceId })
        assertTrue(sources.any { it.jsonObject["id"]!!.jsonPrimitive.content == "openstreetmap" })
    }

    @Test
    fun systemCacheClearWaitsForRunningJobCancellationBeforeDeletingFiles() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        val runnerStarted = CompletableDeferred<Unit>()
        val runnerCancelled = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<ConversionOutcome>()
        application {
            terrainWebModule(
                testDependencies(tempDir).copy(
                    conversionRunner = {
                        runnerStarted.complete(Unit)
                        try {
                            neverCompletes.await()
                        } finally {
                            runnerCancelled.complete(Unit)
                        }
                    }
                )
            )
        }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val jobId = json.parseToJsonElement(client.post("/api/jobs") { setBody(multipartHgtOnly()) }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        runnerStarted.await()
        waitForStatus(client, jobId, "running")

        val clearResponse = client.delete("/api/system/cache") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"running_jobs":true}""")
        }
        val cleared = json.parseToJsonElement(clearResponse.bodyAsText()).jsonObject

        assertEquals(200, clearResponse.status.value)
        assertTrue(runnerCancelled.isCompleted)
        assertEquals(1, cleared["deleted_running_jobs"]!!.jsonPrimitive.int)
        assertFalse(Files.exists(tempDir.resolve("data/jobs/$jobId")))
        assertEquals(404, client.get("/api/jobs/$jobId").status.value)
    }

    @Test
    fun uploadMbtilesExposesProgressUntilReady() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application { terrainWebModule(testDependencies(tempDir)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val uploadId = "upload-test-ready"

        val response = client.post("/api/mbtiles?upload_id=$uploadId") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("source_type", "auto")
                        append("mbtiles", buildMbtilesBytes(tempDir), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=sample.mbtiles")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        })
                    }
                )
            )
        }
        val tilesetId = json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val progress = json.parseToJsonElement(client.get("/api/mbtiles/uploads/$uploadId/progress").bodyAsText()).jsonObject
        assertEquals("ready", progress["stage"]!!.jsonPrimitive.content)
        assertEquals("sample.mbtiles", progress["filename"]!!.jsonPrimitive.content)
        assertEquals(tilesetId, progress["tileset_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun uploadMbtilesProgressRecordsValidationError() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application { terrainWebModule(testDependencies(tempDir)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val uploadId = "upload-test-error"

        client.post("/api/mbtiles?upload_id=$uploadId") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("source_type", "raster")
                        append("mbtiles", "not mbtiles".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=sample.txt")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        })
                    }
                )
            )
        }

        val progress = json.parseToJsonElement(client.get("/api/mbtiles/uploads/$uploadId/progress").bodyAsText()).jsonObject
        assertEquals("error", progress["stage"]!!.jsonPrimitive.content)
        assertEquals("Upload a .mbtiles file", progress["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun uploadMbtilesProgressCleanupRemovesExpiredEntries() {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        val dependencies = testDependencies(tempDir)
        val storage = Storage(dependencies.settings.storageRoot)
        val websocketManager = WebSocketManager(dependencies.json)
        val state = AppState(
            dependencies = dependencies,
            storage = storage,
            websocketManager = websocketManager,
            jobs = JobManager(dependencies, storage, websocketManager, CoroutineScope(SupervisorJob() + Dispatchers.Default)),
            tilesets = ConcurrentHashMap(),
            mbtilesUploadProgress = ConcurrentHashMap(),
            mbtilesUploadProgressUpdatedAt = ConcurrentHashMap(),
            baseSources = BaseMapSourceRepository(storage.databasePath, dependencies.now),
        )
        state.mbtilesUploadProgress["old"] = MBTilesUploadProgress("old", MBTilesUploadStage.READY)
        state.mbtilesUploadProgressUpdatedAt["old"] = 0L
        state.mbtilesUploadProgress["new"] = MBTilesUploadProgress("new", MBTilesUploadStage.VALIDATING)
        state.mbtilesUploadProgressUpdatedAt["new"] = 30L * 60L * 1000L

        cleanupMbtilesUploadProgress(state, nowMillis = 30L * 60L * 1000L + 1L)

        assertFalse(state.mbtilesUploadProgress.containsKey("old"))
        assertFalse(state.mbtilesUploadProgressUpdatedAt.containsKey("old"))
        assertTrue(state.mbtilesUploadProgress.containsKey("new"))
        assertTrue(state.mbtilesUploadProgressUpdatedAt.containsKey("new"))
    }

    @Test
    fun mbtilesDocumentsUseCurrentRequestHost() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application { terrainWebModule(testDependencies(tempDir)) }
        val uploadHost = "upload.internal"
        val requestHost = "phone.internal"
        val dockerClient = createClient { defaultRequest { header(HttpHeaders.Host, uploadHost) } }
        val mobileClient = createClient { defaultRequest { header(HttpHeaders.Host, requestHost) } }
        val response = dockerClient.post("/api/mbtiles") {
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
        val tilesetId = json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val style = mobileClient.get("/api/mbtiles/$tilesetId/style").bodyAsText()

        assertTrue(style.contains("http://$requestHost/api/mbtiles/$tilesetId/{z}/{x}/{y}.png"))
        assertFalse(style.contains("http://$uploadHost/api/mbtiles/$tilesetId/{z}/{x}/{y}.png"))
    }

    @Test
    fun dynamicStylesApplySelectedBaseSourceAndMobileHost() = testApplication {
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
                        ConversionOutcome(BBox(10.0, 20.0, 11.0, 21.0), 1)
                    }
                )
            )
        }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "phone.internal") } }
        val sourceResponse = client.post("/api/base-sources") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"name":"Local Base","url_template":"/base/{z}/{x}/{y}.png","attribution":"Local","max_zoom":18}""")
        }
        val sourceId = json.parseToJsonElement(sourceResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val jobId = json.parseToJsonElement(
            client.post("/api/jobs") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("minzoom", "7")
                            append("maxzoom", "9")
                            append("tile_size", "512")
                            append("scheme", "tms")
                            append("encoding", "mapbox")
                            append("hgt_files", "fake-hgt".toByteArray(), Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=N20E010.hgt")
                                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            })
                        }
                    )
                )
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content
        waitForStatus(client, jobId, "completed")

        val desktopStyle = json.parseToJsonElement(client.get("/api/jobs/$jobId/style?base=$sourceId").bodyAsText()).jsonObject
        val mobileStyle = json.parseToJsonElement(client.get("/api/jobs/$jobId/style-mobile?base=$sourceId").bodyAsText()).jsonObject
        val desktopSources = desktopStyle["sources"]!!.jsonObject
        val mobileSources = mobileStyle["sources"]!!.jsonObject
        val desktopTerrain = desktopSources["terrain-dem"]!!.jsonObject
        val mobileTerrain = mobileSources["terrain-dem"]!!.jsonObject
        val desktopBase = desktopSources["base-map"]!!.jsonObject
        val mobileBase = mobileSources["base-map"]!!.jsonObject

        assertEquals(dynamicJobStyleFixture(jobId, mobile = false), desktopStyle)
        assertEquals(dynamicJobStyleFixture(jobId, mobile = true), mobileStyle)
        assertEquals("tms", desktopTerrain["scheme"]!!.jsonPrimitive.content)
        assertEquals("mapbox", desktopTerrain["encoding"]!!.jsonPrimitive.content)
        assertEquals(512, desktopTerrain["tileSize"]!!.jsonPrimitive.int)
        assertEquals("/api/jobs/$jobId/terrain/{z}/{x}/{y}.png", desktopTerrain["tiles"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("/base/{z}/{x}/{y}.png", desktopBase["tiles"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("Local", desktopBase["attribution"]!!.jsonPrimitive.content)
        assertEquals(18, desktopBase["maxzoom"]!!.jsonPrimitive.int)
        assertEquals("http://phone.internal/api/jobs/$jobId/terrain/{z}/{x}/{y}.png", mobileTerrain["tiles"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("http://phone.internal/base/{z}/{x}/{y}.png", mobileBase["tiles"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(desktopStyle["terrain"], mobileStyle["terrain"])

        val desktopNoBase = json.parseToJsonElement(client.get("/api/jobs/$jobId/style?base=none").bodyAsText()).jsonObject
        val mobileNoBase = json.parseToJsonElement(client.get("/api/jobs/$jobId/style-mobile?base=none").bodyAsText()).jsonObject
        assertFalse(desktopNoBase["sources"]!!.jsonObject.containsKey("base-map"))
        assertFalse(mobileNoBase["sources"]!!.jsonObject.containsKey("base-map"))
        assertTrue(desktopNoBase["layers"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "terrain-hillshade" })
        assertTrue(mobileNoBase["layers"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "terrain-hillshade" })

        val missingDesktop = client.get("/api/jobs/$jobId/style?base=missing")
        val missingMobile = client.get("/api/jobs/$jobId/style-mobile?base=missing")
        assertEquals(404, missingDesktop.status.value)
        assertEquals("Источник подложки не найден", json.parseToJsonElement(missingDesktop.bodyAsText()).jsonObject["detail"]!!.jsonPrimitive.content)
        assertEquals(404, missingMobile.status.value)
        assertEquals("Источник подложки не найден", json.parseToJsonElement(missingMobile.bodyAsText()).jsonObject["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun dynamicMbtilesStylesSupportSelectedBaseNoBaseAndUnknownBase() = testApplication {
        val tempDir = Files.createTempDirectory("terrain-web-test-")
        application { terrainWebModule(testDependencies(tempDir)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "testserver") } }
        val sourceResponse = client.post("/api/base-sources") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"name":"MBTiles Base","url_template":"https://tiles.example.test/{z}/{x}/{y}.png","attribution":"Tiles","max_zoom":16}""")
        }
        val sourceId = json.parseToJsonElement(sourceResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
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

        val desktopStyle = json.parseToJsonElement(client.get("/api/mbtiles/$tilesetId/style?base=$sourceId").bodyAsText()).jsonObject
        val mobileStyle = json.parseToJsonElement(client.get("/api/mbtiles/$tilesetId/style-mobile?base=$sourceId").bodyAsText()).jsonObject
        assertEquals(
            "https://tiles.example.test/{z}/{x}/{y}.png",
            desktopStyle["sources"]!!.jsonObject["base-map"]!!.jsonObject["tiles"]!!.jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(desktopStyle["sources"]!!.jsonObject["base-map"], mobileStyle["sources"]!!.jsonObject["base-map"])

        val desktopNoBase = json.parseToJsonElement(client.get("/api/mbtiles/$tilesetId/style?base=none").bodyAsText()).jsonObject
        val mobileNoBase = json.parseToJsonElement(client.get("/api/mbtiles/$tilesetId/style-mobile?base=none").bodyAsText()).jsonObject
        assertTrue(desktopNoBase["layers"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "tileset-raster" })
        assertFalse(desktopNoBase["sources"]!!.jsonObject.containsKey("base-map"))
        assertFalse(mobileNoBase["sources"]!!.jsonObject.containsKey("base-map"))

        val missingDesktop = client.get("/api/mbtiles/$tilesetId/style?base=missing")
        val missingMobile = client.get("/api/mbtiles/$tilesetId/style-mobile?base=missing")
        assertEquals(404, missingDesktop.status.value)
        assertEquals("Источник подложки не найден", json.parseToJsonElement(missingDesktop.bodyAsText()).jsonObject["detail"]!!.jsonPrimitive.content)
        assertEquals(404, missingMobile.status.value)
        assertEquals("Источник подложки не найден", json.parseToJsonElement(missingMobile.bodyAsText()).jsonObject["detail"]!!.jsonPrimitive.content)
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

    private fun dynamicJobStyleFixture(jobId: String, mobile: Boolean) = json.parseToJsonElement(
        """
        {
          "version": 8,
          "name": "Terrain DEM Style",
          "sources": {
            "terrain-dem": {
              "type": "raster-dem",
              "tiles": [
                "${if (mobile) "http://phone.internal" else ""}/api/jobs/$jobId/terrain/{z}/{x}/{y}.png"
              ],
              "encoding": "mapbox",
              "tileSize": 512,
              "scheme": "tms"
            },
            "base-map": {
              "type": "raster",
              "tiles": [
                "${if (mobile) "http://phone.internal" else ""}/base/{z}/{x}/{y}.png"
              ],
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 18,
              "attribution": "Local"
            }
          },
          "terrain": {
            "source": "terrain-dem",
            "exaggeration": 1.0
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": {
                "background-color": "#0f172a"
              }
            },
            {
              "id": "base-map",
              "type": "raster",
              "source": "base-map"
            },
            {
              "id": "terrain-hillshade",
              "type": "hillshade",
              "source": "terrain-dem"
            }
          ],
          "glyphs": "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
          "center": [
            10.5,
            20.5,
            7
          ],
          "zoom": 7
        }
        """.trimIndent()
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
