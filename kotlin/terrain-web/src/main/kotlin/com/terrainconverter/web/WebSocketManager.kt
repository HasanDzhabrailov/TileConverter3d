package com.terrainconverter.web

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class WebSocketManager(private val json: Json) {
    private val connections = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    fun connect(jobId: String, session: WebSocketSession) {
        connections.computeIfAbsent(jobId) { Collections.synchronizedSet(linkedSetOf()) }.add(session)
    }

    fun disconnect(jobId: String, session: WebSocketSession) {
        connections[jobId]?.let {
            it.remove(session)
            if (it.isEmpty()) connections.remove(jobId)
        }
    }

    suspend fun broadcast(jobId: String, payload: String) {
        val sockets = connections[jobId]?.toList() ?: return
        sockets.forEach { socket ->
            try {
                socket.send(Frame.Text(payload))
            } catch (_: Exception) {
                disconnect(jobId, socket)
            }
        }
    }

    suspend fun broadcastJob(job: JobDetail) {
        broadcast(job.id, json.encodeToString(JobEvent(job = job)))
    }

    suspend fun broadcastLog(jobId: String, line: String) {
        broadcast(jobId, json.encodeToString(LogEvent(line = line)))
    }
}
