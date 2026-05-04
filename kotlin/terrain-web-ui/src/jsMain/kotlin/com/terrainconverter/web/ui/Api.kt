package com.terrainconverter.web.ui

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket
import org.w3c.fetch.RequestInit
import org.w3c.xhr.FormData

private val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

object ApiClient {
    suspend fun listJobs(): List<Job> = getJson("/api/jobs")

    suspend fun getJob(jobId: String): Job = getJson("/api/jobs/$jobId")

    suspend fun createJob(formData: FormData): Job = sendForm("/api/jobs", formData)

    suspend fun listMbtilesTilesets(): List<MbtilesTileset> = getJson("/api/mbtiles")

    suspend fun uploadMbtilesTileset(formData: FormData): MbtilesTileset = sendForm("/api/mbtiles", formData)

    suspend fun getServerInfo(): ServerInfo = getJson("/api/server-info")

    suspend fun loadBootstrap(): BootstrapData = BootstrapData(
        serverInfo = getServerInfo(),
        jobs = listJobs(),
        tilesets = listMbtilesTilesets(),
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
            onError(IllegalStateException("WebSocket error for job $jobId"))
        }
        return BrowserJobSocket(socket)
    }

    fun artifactUrl(path: String?): String? = path

    fun styleUrl(jobId: String): String = "/api/jobs/$jobId/style"

    fun absoluteUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        return "${window.location.protocol}//${window.location.host}$path"
    }

    private suspend inline fun <reified T> getJson(path: String): T {
        val response = window.fetch(path).await()
        return response.decodeJson(defaultMessage = "Request failed: $path")
    }

    private suspend inline fun <reified T> sendForm(path: String, formData: FormData): T {
        val request = js("({})").unsafeCast<RequestInit>()
        request.method = "POST"
        request.body = formData
        val response = window.fetch(path, request).await()
        return response.decodeJson(defaultMessage = "Request failed: $path")
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
