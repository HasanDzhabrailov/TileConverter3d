package com.terrainconverter.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.plugins.origin
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

fun isUsableHost(host: String?): Boolean = !host.isNullOrBlank() && host !in setOf(".", "0.0.0.0", "localhost", "::", "::1")

fun isPrivateIpv4(address: String): Boolean {
    val octets = address.split('.')
    if (octets.size != 4) return false
    val first = octets[0].toIntOrNull() ?: return false
    val second = octets[1].toIntOrNull() ?: return false
    return first == 10 || (first == 192 && second == 168) || (first == 172 && second in 16..31)
}

fun resolvePublicHost(requestHost: String): String {
    val configured = System.getenv("TERRAIN_WEB_PUBLIC_HOST")?.trim().orEmpty()
    if (isUsableHost(configured)) return configured
    if (requestHost !in setOf("127.0.0.1", "localhost", "::1") && isUsableHost(requestHost)) return requestHost
    if (isRunningInContainer()) return requestHost
    runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress("8.8.8.8", 80))
            val address = socket.localAddress.hostAddress
            if (!address.startsWith("127.") && isUsableHost(address)) return address
        }
    }
    (NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) } ?: emptyList()).forEach { iface ->
        if (!iface.isUp || iface.isLoopback || iface.isVirtual) return@forEach
        Collections.list(iface.inetAddresses).filterIsInstance<Inet4Address>().map { it.hostAddress }.forEach { address ->
            if (!address.startsWith("127.") && isPrivateIpv4(address) && isUsableHost(address)) return address
        }
    }
    runCatching {
        InetAddress.getLocalHost().hostAddress?.let { if (!it.startsWith("127.") && isUsableHost(it)) return it }
    }
    return requestHost
}

private fun isRunningInContainer(): Boolean = Files.exists(Path.of("/.dockerenv"))

fun requestScheme(call: ApplicationCall): String = call.request.origin.scheme

fun requestPort(call: ApplicationCall): Int = call.request.origin.serverPort

fun baseUrl(scheme: String, host: String, port: Int): String {
    val defaultPort = if (scheme == "https") 443 else 80
    val portSuffix = if (port == defaultPort) "" else ":$port"
    return "$scheme://$host$portSuffix"
}

fun publicBaseUrl(scheme: String, requestHost: String, port: Int): String = baseUrl(scheme, resolvePublicHost(requestHost), port)

fun requestBaseUrl(call: ApplicationCall): String = baseUrl(requestScheme(call), call.request.host(), requestPort(call))

fun buildServerInfo(requestHost: String, requestBaseUrl: String, scheme: String, port: Int): ServerInfo {
    val publicHost = resolvePublicHost(requestHost)
    val addresses = mutableListOf(
        ServerAddress(
            id = "mobile",
            label = "Mobile / Wi-Fi",
            host = publicHost,
            baseUrl = baseUrl(scheme, publicHost, port),
            description = "Use this address from a phone or another device in the same local network.",
        ),
        ServerAddress(
            id = "localhost",
            label = "This computer",
            host = "127.0.0.1",
            baseUrl = baseUrl(scheme, "127.0.0.1", port),
            description = "Use this address only on the same computer where the server is running.",
        ),
    )
    if (requestHost !in setOf(publicHost, "127.0.0.1", "localhost", "::1")) {
        addresses += ServerAddress(
            id = "request-host",
            label = "Current browser host",
            host = requestHost,
            baseUrl = requestBaseUrl,
            description = "This is the host from which the current browser opened the UI.",
        )
    }
    return ServerInfo(addresses)
}
