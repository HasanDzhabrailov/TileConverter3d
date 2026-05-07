package com.terrainconverter.web

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class BaseMapSourceRepository(
    private val databasePath: Path,
    private val now: () -> String,
) {
    init {
        Class.forName("org.sqlite.JDBC")
        databasePath.parent?.let { java.nio.file.Files.createDirectories(it) }
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS base_map_sources (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        url_template TEXT NOT NULL,
                        attribution TEXT,
                        max_zoom INTEGER NOT NULL,
                        is_builtin INTEGER NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            seedBuiltinSources(connection)
        }
    }

    @Synchronized
    fun list(): List<BaseMapSource> = withConnection { connection ->
        connection.prepareStatement(
            "SELECT id, name, url_template, attribution, max_zoom, is_builtin, created_at, updated_at FROM base_map_sources ORDER BY is_builtin DESC, name COLLATE NOCASE"
        ).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toBaseMapSource())
                }
            }
        }
    }

    @Synchronized
    fun create(request: BaseMapSourceRequest): BaseMapSource {
        val normalized = validate(request)
        val timestamp = now()
        val id = generateId(normalized.name)
        withConnection { connection ->
            connection.prepareStatement(
                "INSERT INTO base_map_sources (id, name, url_template, attribution, max_zoom, is_builtin, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, ?, ?)"
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, normalized.name)
                statement.setString(3, normalized.urlTemplate)
                statement.setString(4, normalized.attribution)
                statement.setInt(5, normalized.maxZoom)
                statement.setString(6, timestamp)
                statement.setString(7, timestamp)
                statement.executeUpdate()
            }
        }
        return get(id) ?: error("Created base source was not persisted")
    }

    @Synchronized
    fun update(id: String, request: BaseMapSourceRequest): BaseMapSource? {
        val current = get(id) ?: return null
        if (current.isBuiltin) throw BuiltinBaseMapSourceException("Builtin base sources cannot be edited")
        val normalized = validate(request)
        withConnection { connection ->
            connection.prepareStatement(
                "UPDATE base_map_sources SET name = ?, url_template = ?, attribution = ?, max_zoom = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, normalized.name)
                statement.setString(2, normalized.urlTemplate)
                statement.setString(3, normalized.attribution)
                statement.setInt(4, normalized.maxZoom)
                statement.setString(5, now())
                statement.setString(6, id)
                statement.executeUpdate()
            }
        }
        return get(id)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val current = get(id) ?: return false
        if (current.isBuiltin) throw BuiltinBaseMapSourceException("Builtin base sources cannot be deleted")
        withConnection { connection ->
            connection.prepareStatement("DELETE FROM base_map_sources WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
        return true
    }

    @Synchronized
    fun deleteCustomSources(): Int = withConnection { connection ->
        connection.prepareStatement("DELETE FROM base_map_sources WHERE is_builtin = 0").use { statement ->
            statement.executeUpdate()
        }
    }

    @Synchronized
    fun get(id: String): BaseMapSource? = withConnection { connection ->
        connection.prepareStatement(
            "SELECT id, name, url_template, attribution, max_zoom, is_builtin, created_at, updated_at FROM base_map_sources WHERE id = ?"
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toBaseMapSource() else null }
        }
    }

    private fun validate(request: BaseMapSourceRequest): BaseMapSourceRequest {
        val name = request.name.trim()
        val urlTemplate = request.urlTemplate.trim()
        val attribution = request.attribution?.trim()?.takeIf { it.isNotEmpty() }
        if (name.isBlank()) throw BaseMapSourceValidationException("name is required")
        if (urlTemplate.isBlank()) throw BaseMapSourceValidationException("url_template is required")
        if (!urlTemplate.startsWith("http://") && !urlTemplate.startsWith("https://") && !urlTemplate.startsWith("/")) {
            throw BaseMapSourceValidationException("url_template must start with http://, https://, or /")
        }
        listOf("{z}", "{x}", "{y}").forEach { token ->
            if (!urlTemplate.contains(token)) throw BaseMapSourceValidationException("url_template must contain {z}, {x}, and {y}")
        }
        if (request.maxZoom !in 1..22) throw BaseMapSourceValidationException("max_zoom must be in 1..22")
        return BaseMapSourceRequest(name, urlTemplate, attribution, request.maxZoom)
    }

    private fun generateId(name: String): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "source" }
        var candidate = base
        var suffix = 2
        while (get(candidate) != null) {
            candidate = "$base-$suffix"
            suffix += 1
        }
        return candidate
    }

    private fun seedBuiltinSources(connection: Connection) {
        val timestamp = now()
        builtinBaseMapSources.forEach { source ->
            connection.prepareStatement(
                """
                INSERT INTO base_map_sources (id, name, url_template, attribution, max_zoom, is_builtin, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    url_template = excluded.url_template,
                    attribution = excluded.attribution,
                    max_zoom = excluded.max_zoom,
                    is_builtin = 1,
                    updated_at = CASE
                        WHEN name IS NOT excluded.name
                            OR url_template IS NOT excluded.url_template
                            OR attribution IS NOT excluded.attribution
                            OR max_zoom IS NOT excluded.max_zoom
                            OR is_builtin IS NOT 1
                        THEN excluded.updated_at
                        ELSE updated_at
                    END
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, source.id)
                statement.setString(2, source.name)
                statement.setString(3, source.urlTemplate)
                statement.setString(4, source.attribution)
                statement.setInt(5, source.maxZoom)
                statement.setString(6, source.createdAt ?: timestamp)
                statement.setString(7, timestamp)
                statement.executeUpdate()
            }
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").use(block)
}

class BaseMapSourceValidationException(message: String) : IllegalArgumentException(message)

class BuiltinBaseMapSourceException(message: String) : IllegalStateException(message)

private fun java.sql.ResultSet.toBaseMapSource(): BaseMapSource = BaseMapSource(
    id = getString("id"),
    name = getString("name"),
    urlTemplate = getString("url_template"),
    attribution = getString("attribution"),
    maxZoom = getInt("max_zoom"),
    isBuiltin = getInt("is_builtin") == 1,
    createdAt = getString("created_at"),
    updatedAt = getString("updated_at"),
)

private val builtinBaseMapSources = listOf(
    BaseMapSource(
        id = "openstreetmap",
        name = "OpenStreetMap",
        urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        attribution = "© OpenStreetMap contributors",
        maxZoom = 19,
        isBuiltin = true,
    ),
    BaseMapSource(
        id = "opentopomap",
        name = "OpenTopoMap",
        urlTemplate = "https://tile.opentopomap.org/{z}/{x}/{y}.png",
        attribution = "© OpenTopoMap contributors, © OpenStreetMap contributors",
        maxZoom = 17,
        isBuiltin = true,
    ),
    BaseMapSource(
        id = "cartodb-positron",
        name = "CartoDB Positron",
        urlTemplate = "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
        attribution = "© CARTO, © OpenStreetMap contributors",
        maxZoom = 20,
        isBuiltin = true,
    ),
    BaseMapSource(
        id = "cartodb-dark-matter",
        name = "CartoDB Dark Matter",
        urlTemplate = "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        attribution = "© CARTO, © OpenStreetMap contributors",
        maxZoom = 20,
        isBuiltin = true,
    ),
    BaseMapSource(
        id = "esri-satellite",
        name = "Спутник ESRI",
        urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        attribution = "Tiles © Esri",
        maxZoom = 19,
        isBuiltin = true,
    ),
    BaseMapSource(
        id = "google-satellite",
        name = "Спутник Google",
        urlTemplate = "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
        attribution = "© Google",
        maxZoom = 20,
        isBuiltin = true,
    ),
    BaseMapSource(
        id = "google-roadmap",
        name = "Google Maps",
        urlTemplate = "https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}",
        attribution = "© Google",
        maxZoom = 20,
        isBuiltin = true,
    ),
    BaseMapSource(
        id = "yandex-satellite",
        name = "Спутник Яндекс",
        urlTemplate = "https://core-sat.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}&scale=1&lang=ru_RU",
        attribution = "© Яндекс",
        maxZoom = 19,
        isBuiltin = true,
    ),
    BaseMapSource(
        id = "yandex-map",
        name = "Яндекс Карты",
        urlTemplate = "https://core-renderer-tiles.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&scale=1&lang=ru_RU",
        attribution = "© Яндекс",
        maxZoom = 19,
        isBuiltin = true,
    ),
    BaseMapSource(
        id = "none",
        name = "Без подложки",
        urlTemplate = "",
        attribution = null,
        maxZoom = 22,
        isBuiltin = true,
    ),
)
