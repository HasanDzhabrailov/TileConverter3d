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

fun configuredPublicBaseUrl(): String? {
    val configured = System.getenv("TERRAIN_WEB_PUBLIC_URL")?.trim().orEmpty().trimEnd('/')
    return configured.takeIf { it.startsWith("http://") }
}

fun isPrivateIpv4(address: String): Boolean {
    val octets = address.split('.')
    if (octets.size != 4) return false
    val first = octets[0].toIntOrNull() ?: return false
    val second = octets[1].toIntOrNull() ?: return false
    return first == 10 || (first == 192 && second == 168) || (first == 172 && second in 16..31)
}

fun isLanIpv4Host(address: String): Boolean {
    return !address.startsWith("127.") &&
        isPrivateIpv4(address) &&
        isUsableHost(address)
}

fun configuredLanHost(): String? {
    val configured = System.getenv("TERRAIN_WEB_LAN_HOST")?.trim().orEmpty()
    if (isLanIpv4Host(configured)) return configured

    val configuredFile = System.getenv("TERRAIN_WEB_LAN_HOST_FILE")?.trim().orEmpty()
    val hostFile = configuredFile.ifBlank { "/app/config/lan-host.txt" }
    return runCatching { Files.readString(Path.of(hostFile)).trim() }
        .getOrNull()
        ?.takeIf { isLanIpv4Host(it) }
}

fun resolveLocalDomainHost(): String? {
    val configured = System.getenv("TERRAIN_WEB_LOCAL_DOMAIN")?.trim().orEmpty()
    if (isUsableHost(configured)) return configured.removePrefix("http://").substringBefore('/').substringBefore(':')

    val hostName = listOf(
        System.getenv("TERRAIN_WEB_HOST_COMPUTERNAME"),
        System.getenv("TERRAIN_WEB_HOST_HOSTNAME"),
    ).firstOrNull { isUsableHost(it) }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()

    val clean = hostName
        ?.trim()
        ?.substringBefore('.')
        ?.filter { it.isLetterOrDigit() || it == '-' }
        ?.takeIf { isUsableHost(it) }
    return clean?.let { "$it.local" }
}

fun lanIpDomainHost(address: String): String = address.replace('.', '-') + ".sslip.io"

fun resolvePublicHost(requestHost: String): String {
    val configured = System.getenv("TERRAIN_WEB_PUBLIC_HOST")?.trim().orEmpty()
    if (isUsableHost(configured)) return configured

    configuredLanHost()?.let { return lanIpDomainHost(it) }

    // If requestHost is already a good non-localhost address, use it. Avoid .local as a generated
    // public URL because many Android clients do not resolve mDNS names reliably.
    if (requestHost !in setOf("127.0.0.1", "localhost", "::1") && !requestHost.endsWith(".local") && isUsableHost(requestHost)) {
        return requestHost
    }

    if (!isRunningInContainer()) {
        resolveAllLanHosts().firstOrNull()?.let { return lanIpDomainHost(it) }
    }
    resolveLocalDomainHost()?.let { return it }

    if (isRunningInContainer()) return requestHost
    
    // Try to find LAN IP through various methods
    var foundAddress: String? = null
    
    // Method 1: Try to get IP from default gateway connection
    runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress("8.8.8.8", 80))
            val address = socket.localAddress.hostAddress
            if (isLanIpv4Host(address)) {
                foundAddress = address
            }
        }
    }
    
    if (foundAddress != null) return foundAddress!!
    
    // Method 2: Iterate network interfaces
    run findLan@{
        (NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) } ?: emptyList()).forEach { iface ->
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) return@forEach
            if (isDockerInterface(iface.name)) return@forEach
            Collections.list(iface.inetAddresses).filterIsInstance<Inet4Address>().map { it.hostAddress }.forEach { address ->
                if (isLanIpv4Host(address)) {
                    foundAddress = address
                    return@findLan
                }
            }
        }
    }
    
    if (foundAddress != null) return foundAddress!!
    
    // Method 3: Try localhost hostname
    runCatching {
        InetAddress.getLocalHost().hostAddress?.let { 
            if (isLanIpv4Host(it)) {
                foundAddress = it
            }
        }
    }
    
    // Return found LAN IP or fallback to requestHost
    return foundAddress ?: requestHost
}

fun isDockerInterface(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("docker") || lower.startsWith("br-") || lower.startsWith("veth") || lower.contains("docker")
}

fun resolveAllLanHosts(): List<String> {
    val hosts = mutableListOf<String>()
    configuredLanHost()?.let { hosts.add(it) }

    val configured = System.getenv("TERRAIN_WEB_PUBLIC_HOST")?.trim().orEmpty()
    if (isUsableHost(configured)) {
        if (configured !in hosts) hosts.add(configured)
        return hosts
    }

    if (isRunningInContainer()) return hosts

    runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress("8.8.8.8", 80))
            val address = socket.localAddress.hostAddress
            if (isLanIpv4Host(address)) {
                hosts.add(address)
            }
        }
    }

    (NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) } ?: emptyList()).forEach { iface ->
        if (!iface.isUp || iface.isLoopback || iface.isVirtual) return@forEach
        // Skip Docker interfaces
        if (isDockerInterface(iface.name)) return@forEach
        Collections.list(iface.inetAddresses).filterIsInstance<Inet4Address>().map { it.hostAddress }.forEach { address ->
            if (isLanIpv4Host(address) && address !in hosts) {
                hosts.add(address)
            }
        }
    }

    runCatching {
        InetAddress.getLocalHost().hostAddress?.let {
            if (isLanIpv4Host(it) && it !in hosts) {
                hosts.add(it)
            }
        }
    }

    return hosts
}

private fun isRunningInContainer(): Boolean = Files.exists(Path.of("/.dockerenv"))

fun requestScheme(call: ApplicationCall): String = call.request.origin.scheme

fun requestPort(call: ApplicationCall): Int = call.request.origin.serverPort

fun baseUrl(scheme: String, host: String, port: Int): String {
    val defaultPort = if (scheme == "https") 443 else 80
    val portSuffix = if (port == defaultPort) "" else ":$port"
    return "$scheme://$host$portSuffix"
}

fun publicBaseUrl(scheme: String, requestHost: String, port: Int): String {
    configuredPublicBaseUrl()?.let { return it }
    return baseUrl(scheme, resolvePublicHost(requestHost), port)
}

fun requestBaseUrl(call: ApplicationCall): String = baseUrl(requestScheme(call), call.request.host(), requestPort(call))

fun buildServerInfo(requestHost: String, requestBaseUrl: String, scheme: String, port: Int): ServerInfo {
    val allLanHosts = resolveAllLanHosts()
    val addresses = mutableListOf<ServerAddress>()

    configuredPublicBaseUrl()?.let { publicUrl ->
        addresses += ServerAddress(
            id = "public",
            label = "Public URL",
            host = publicUrl.substringAfter("://").substringBefore('/'),
            baseUrl = publicUrl,
            description = "Use this externally reachable address from other networks and mobile clients.",
        )
    }

    allLanHosts.firstOrNull()?.let { lanHost ->
        val domain = lanIpDomainHost(lanHost)
        addresses += ServerAddress(
            id = "lan-domain",
            label = "Mobile LAN domain",
            host = domain,
            baseUrl = baseUrl("http", domain, port),
            description = "HTTP domain that resolves to the detected LAN IP. Use it from another device in the same network.",
        )
    }

    resolveLocalDomainHost()?.let { localDomain ->
        addresses += ServerAddress(
            id = "local-domain",
            label = "Local network domain",
            host = localDomain,
            baseUrl = baseUrl("http", localDomain, port),
            description = "Try this HTTP .local address from another device in the same network.",
        )
    }

    if (allLanHosts.isNotEmpty()) {
        addresses += ServerAddress(
            id = "lan-primary",
            label = "Wi-Fi / LAN",
            host = allLanHosts.first(),
            baseUrl = baseUrl(scheme, allLanHosts.first(), port),
            description = "Use this address from a phone or another device in the same local network.",
        )

        allLanHosts.drop(1).forEachIndexed { index, host ->
            addresses += ServerAddress(
                id = "lan-alt-$index",
                label = "Alternative LAN",
                host = host,
                baseUrl = baseUrl(scheme, host, port),
                description = "Alternative local network address.",
            )
        }
    }

    addresses += ServerAddress(
        id = "localhost",
        label = "This computer",
        host = "127.0.0.1",
        baseUrl = baseUrl(scheme, "127.0.0.1", port),
        description = "Use this address only on the same computer where the server is running.",
    )

    if (requestHost !in allLanHosts && requestHost !in setOf("127.0.0.1", "localhost", "::1")) {
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
