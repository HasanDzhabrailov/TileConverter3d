package com.terrainconverter.core

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

private const val MBTILES_SCHEMA = """
CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT);
CREATE TABLE IF NOT EXISTS tiles (
    zoom_level INTEGER,
    tile_column INTEGER,
    tile_row INTEGER,
    tile_data BLOB
);
CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row);
CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name);
"""

fun xyzToTmsRow(zoom: Int, y: Int): Int = ((1 shl zoom) - 1) - y

class MbtilesWriter(private val path: Path) : AutoCloseable {
    private val batchSize = 1024
    private val tileBatch = ArrayList<TileRow>(batchSize)
    private val connection: Connection
    private val metadataStatement: java.sql.PreparedStatement
    private val tileStatement: java.sql.PreparedStatement

    init {
        Class.forName("org.sqlite.JDBC")
        path.parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=MEMORY")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA temp_store=MEMORY")
            statement.execute("PRAGMA cache_size=-65536")
            statement.executeUpdate(MBTILES_SCHEMA)
        }
        connection.autoCommit = false
        metadataStatement = connection.prepareStatement("INSERT OR REPLACE INTO metadata(name, value) VALUES(?, ?)")
        tileStatement = connection.prepareStatement(
            "INSERT OR REPLACE INTO tiles(zoom_level, tile_column, tile_row, tile_data) VALUES(?, ?, ?, ?)"
        )
    }

    fun writeMetadata(metadata: Map<String, String>) {
        for ((name, value) in metadata.toSortedMap()) {
            metadataStatement.setString(1, name)
            metadataStatement.setString(2, value)
            metadataStatement.addBatch()
        }
        metadataStatement.executeBatch()
        metadataStatement.clearBatch()
        connection.commit()
    }

    fun writeTile(zoom: Int, x: Int, y: Int, tileData: ByteArray) {
        tileBatch += TileRow(zoom, x, xyzToTmsRow(zoom, y), tileData)
        if (tileBatch.size >= batchSize) {
            flushTiles()
        }
    }

    private fun flushTiles() {
        if (tileBatch.isEmpty()) {
            return
        }
        for (row in tileBatch) {
            tileStatement.setInt(1, row.zoom)
            tileStatement.setInt(2, row.x)
            tileStatement.setInt(3, row.y)
            tileStatement.setBytes(4, row.tileData)
            tileStatement.addBatch()
        }
        tileStatement.executeBatch()
        tileStatement.clearBatch()
        connection.commit()
        tileBatch.clear()
    }

    override fun close() {
        try {
            flushTiles()
        } finally {
            try {
                metadataStatement.close()
            } finally {
                try {
                    tileStatement.close()
                } finally {
                    connection.close()
                }
            }
        }
    }
}

private data class TileRow(
    val zoom: Int,
    val x: Int,
    val y: Int,
    val tileData: ByteArray,
)
