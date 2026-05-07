package com.terrainconverter.web.ui

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket
import org.w3c.fetch.RequestInit
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Date
import kotlin.js.Promise

private val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

object ApiClient {
    suspend fun listJobs(): List<Job> = getJson("/api/jobs")

    suspend fun getJob(jobId: String): Job = getJson("/api/jobs/$jobId")

    suspend fun createJob(formData: FormData): Job = sendForm("/api/jobs", formData)

    suspend fun listMbtilesTilesets(): List<MbtilesTileset> = getJson("/api/mbtiles")

    suspend fun uploadMbtilesTileset(
        formData: FormData,
        uploadId: String? = null,
        onUploadProgress: ((BrowserUploadProgress) -> Unit)? = null,
    ): MbtilesTileset = if (onUploadProgress == null) {
        sendForm("/api/mbtiles", formData)
    } else {
        startMbtilesTilesetUpload(formData, uploadId ?: newUploadId(), onUploadProgress).promise.await()
    }

    fun startMbtilesTilesetUpload(
        formData: FormData,
        uploadId: String = newUploadId(),
        onUploadProgress: (BrowserUploadProgress) -> Unit,
    ): MbtilesUploadRequest = startFormWithUploadProgress("/api/mbtiles?upload_id=$uploadId", formData, uploadId, onUploadProgress)

    suspend fun getMbtilesUploadProgress(uploadId: String): MbtilesUploadProgress = getJson("/api/mbtiles/uploads/$uploadId/progress")

    suspend fun listBaseSources(): List<BaseMapSource> = getJson("/api/base-sources")

    suspend fun createBaseSource(request: BaseMapSourceRequest): BaseMapSource = sendJson("POST", "/api/base-sources", request)

    suspend fun deleteBaseSource(sourceId: String): Boolean {
        val response = window.fetch("/api/base-sources/$sourceId", js("({ method: 'DELETE' })").unsafeCast<RequestInit>()).await()
        return response.ok
    }

    suspend fun getServerInfo(): ServerInfo = getJson("/api/server-info")

    suspend fun getStorageStats(): StorageStats = getJson("/api/system/storage")

    suspend fun clearCache(request: CacheClearRequest): CacheClearResult = sendJson("DELETE", "/api/system/cache", request)

    suspend fun loadBootstrap(): BootstrapData = BootstrapData(
        serverInfo = getServerInfo(),
        jobs = listJobs(),
        tilesets = listMbtilesTilesets(),
        baseSources = listBaseSources(),
        storageStats = getStorageStats(),
    )

    fun connectJob(
        jobId: String,
        onEvent: (WsEvent) -> Unit,
        onError: (Throwable) -> Unit = {},
    ): JobSocket {
        val protocol = if (window.location.protocol == "https:") "wss" else "ws"
        val socket = WebSocket("$protocol://${window.location.host}/ws/jobs/$jobId")
        socket.onmessage = { event ->
            decodeSocketEvent(event, onEvent, onError)
        }
        socket.onerror = {
            onError(IllegalStateException("Ошибка WebSocket для задачи $jobId"))
        }
        return BrowserJobSocket(socket)
    }

    fun artifactUrl(path: String?): String? = path

    fun styleUrl(jobId: String): String = "/api/jobs/$jobId/style"

    fun mobileStyleUrl(jobId: String): String = "/api/jobs/$jobId/style-mobile"

    fun absoluteUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        return "${window.location.protocol}//${window.location.host}$path"
    }

    private suspend inline fun <reified T> getJson(path: String): T {
        val response = window.fetch(path).await()
        return response.decodeJson(defaultMessage = "Не удалось выполнить запрос: $path")
    }

    private suspend inline fun <reified T> sendForm(path: String, formData: FormData): T {
        val request = js("({})").unsafeCast<RequestInit>()
        request.method = "POST"
        request.body = formData
        val response = window.fetch(path, request).await()
        return response.decodeJson(defaultMessage = "Не удалось выполнить запрос: $path")
    }

    private suspend inline fun <reified T, reified B> sendJson(method: String, path: String, body: B): T {
        val request = js("({})").unsafeCast<RequestInit>()
        request.method = method
        request.asDynamic().headers = js("({ 'Content-Type': 'application/json' })")
        request.body = apiJson.encodeToString(body)
        val response = window.fetch(path, request).await()
        return response.decodeJson(defaultMessage = "Не удалось выполнить запрос: $path")
    }

    private fun startFormWithUploadProgress(
        path: String,
        formData: FormData,
        uploadId: String,
        onUploadProgress: (BrowserUploadProgress) -> Unit,
    ): MbtilesUploadRequest {
        val request = XMLHttpRequest()
        val promise = Promise<String> { resolve, reject ->
            var sawProgress = false
            request.open("POST", path)
            request.upload.onprogress = { event ->
                val progress = event.asDynamic()
                val total = progress.total.unsafeCast<Double>()
                if (progress.lengthComputable == true && total > 0.0) {
                    sawProgress = true
                    val loaded = progress.loaded.unsafeCast<Double>()
                    onUploadProgress(BrowserUploadProgress(loaded = loaded, total = total))
                }
                null
            }
            request.onload = {
                val responseText = request.responseText
                if (request.status.toInt() in 200..299) {
                    if (!sawProgress) {
                        onUploadProgress(BrowserUploadProgress(loaded = 1.0, total = 1.0))
                    }
                    resolve(responseText)
                } else {
                    reject(IllegalStateException(responseText.ifBlank { "Не удалось выполнить запрос: $path" }))
                }
                null
            }
            request.onerror = {
                reject(IllegalStateException("Не удалось выполнить запрос: $path"))
                null
            }
            request.onabort = {
                reject(IllegalStateException("Запрос отменен: $path"))
                null
            }
            request.send(formData)
        }
        return MbtilesUploadRequest(
            uploadId = uploadId,
            promise = promise.then { body -> apiJson.decodeFromString<MbtilesTileset>(body) },
            cancel = { request.abort() },
        )
    }

    private fun decodeSocketEvent(
        event: MessageEvent,
        onEvent: (WsEvent) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        runCatching {
            apiJson.decodeFromString<WsEvent>(event.data.toString())
        }.onSuccess(onEvent).onFailure(onError)
    }
}

data class BrowserUploadProgress(val loaded: Double, val total: Double) {
    val percent: Int = ((loaded / total) * 100.0).toInt().coerceIn(0, 100)
}

class MbtilesUploadRequest(
    val uploadId: String,
    val promise: Promise<MbtilesTileset>,
    private val cancel: () -> Unit,
) {
    fun cancel() = cancel.invoke()
}

private fun newUploadId(): String = "upload-${Date.now().toLong()}-${(js("Math.random()") as Double).toString().substringAfter('.')}"

interface JobSocket {
    fun close()
}

private class BrowserJobSocket(private val socket: WebSocket) : JobSocket {
    override fun close() {
        socket.close()
    }
}

private suspend inline fun <reified T> org.w3c.fetch.Response.decodeJson(defaultMessage: String): T {
    val body = text().await()
    if (!ok) {
        throw IllegalStateException(body.ifBlank { defaultMessage })
    }
    return apiJson.decodeFromString(body)
}
