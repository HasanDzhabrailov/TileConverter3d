package com.terrainconverter.web

import com.terrainconverter.core.xyzToTmsRow
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists

private val mediaTypes = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "pbf" to "application/x-protobuf",
)

private fun sniffMediaType(tileData: ByteArray): String {
    return when {
        tileData.copyOfRange(0, minOf(tileData.size, 4)).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) -> "image/png"
        tileData.size >= 3 && tileData[0] == 0xFF.toByte() && tileData[1] == 0xD8.toByte() && tileData[2] == 0xFF.toByte() -> "image/jpeg"
        tileData.size >= 12 && String(tileData.copyOfRange(0, 4)) == "RIFF" && String(tileData.copyOfRange(8, 12)) == "WEBP" -> "image/webp"
        else -> "application/octet-stream"
    }
}

class MBTilesServer(private val path: Path) {
    init {
        Class.forName("org.sqlite.JDBC")
    }

    fun exists(): Boolean = path.exists()

    private fun <T> withConnection(block: (Connection) -> T): T {
        return DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use(block)
    }

    fun getMetadata(): Map<String, String> {
        if (!exists()) return emptyMap()
        return withConnection { connection ->
            connection.prepareStatement("SELECT name, value FROM metadata").use { statement ->
                statement.executeQuery().use { rs ->
                    linkedMapOf<String, String>().apply {
                        while (rs.next()) {
                            put(rs.getString(1), rs.getString(2))
                        }
                    }
                }
            }
        }
    }

    fun getFormat(): String = getMetadata()["format"]?.lowercase() ?: "png"

    fun getName(): String? = getMetadata()["name"]

    fun getAttribution(): String? = getMetadata()["attribution"]

    fun getMinzoom(): Int? = getMetadata()["minzoom"]?.toDoubleOrNull()?.toInt()

    fun getMaxzoom(): Int? = getMetadata()["maxzoom"]?.toDoubleOrNull()?.toInt()

    fun getBounds(): BBox? {
        val parts = getMetadata()["bounds"]?.split(",") ?: return null
        if (parts.size != 4) return null
        val values = parts.map { it.toDoubleOrNull() ?: return null }
        return BBox(values[0], values[1], values[2], values[3])
    }

    fun getView(): MapView? {
        val metadata = getMetadata()
        metadata["center"]?.split(",")?.let { parts ->
            if (parts.size == 3) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                val zoom = parts[2].toDoubleOrNull()?.toInt()
                if (lon != null && lat != null && zoom != null) {
                    return MapView(lon, lat, zoom)
                }
            }
        }
        val bounds = getBounds() ?: return null
        return MapView(
            centerLon = (bounds.west + bounds.east) / 2.0,
            centerLat = (bounds.south + bounds.north) / 2.0,
            zoom = metadata["minzoom"]?.toDoubleOrNull()?.toInt() ?: 0,
        )
    }

    fun getTile(z: Int, x: Int, y: Int): Pair<ByteArray, String>? {
        if (!exists()) return null
        return withConnection { connection ->
            val fmt = connection.prepareStatement("SELECT value FROM metadata WHERE name='format'").use { statement ->
                statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            connection.prepareStatement(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?"
            ).use { statement ->
                statement.setInt(1, z)
                statement.setInt(2, x)
                statement.setInt(3, xyzToTmsRow(z, y))
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    val tileData = rs.getBytes(1)
                    tileData to (mediaTypes[fmt?.lowercase()] ?: sniffMediaType(tileData))
                }
            }
        }
    }
}
