package com.terrainconverter.web

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.writeBytes

/**
 * Helper to generate committed MBTiles fixtures for upload parity testing.
 * Run this to regenerate the binary fixtures in kotlin/parity-fixtures/inputs/mbtiles/uploads/
 */
object MbtilesFixtureGenerator {
    
    // Minimal valid 1x1 PNG (grayscale, 1 pixel)
    private val SINGLE_PIXEL_PNG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,  // PNG signature
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,           // IHDR chunk
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,           // 1x1 dimensions
        0x08, 0x00, 0x00, 0x00, 0x00,                             // 8-bit grayscale
        0x7A.toByte(), 0x8F.toByte(), 0x58, 0x1D,                 // IHDR CRC
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,           // IDAT chunk
        0x78, 0x9C.toByte(), 0x63, 0x60, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
        0x5A.toByte(), 0x9C.toByte(), 0x3E, 0x71,                 // IDAT CRC
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,           // IEND chunk
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte()                  // IEND CRC
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val outputDir = if (args.isNotEmpty()) {
            Path.of(args[0])
        } else {
            Path.of("kotlin/parity-fixtures/inputs/mbtiles/uploads")
        }
        
        Files.createDirectories(outputDir)
        Class.forName("org.sqlite.JDBC")
        
        generateValidRaster(outputDir.resolve("raster.mbtiles"))
        generateRasterDem(outputDir.resolve("raster-dem.mbtiles"))
        generateMalformedMetadata(outputDir.resolve("malformed-metadata.mbtiles"))
        generateMissingMetadata(outputDir.resolve("missing-metadata.mbtiles"))
        
        println("Generated MBTiles fixtures in: $outputDir")
    }
    
    private fun generateValidRaster(path: Path) {
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
                stmt.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
                
                stmt.execute("INSERT INTO metadata VALUES ('format', 'png')")
                stmt.execute("INSERT INTO metadata VALUES ('name', 'Test Raster Tileset')")
                stmt.execute("INSERT INTO metadata VALUES ('description', 'Test tileset for parity verification')")
                stmt.execute("INSERT INTO metadata VALUES ('attribution', 'Test Attribution')")
                stmt.execute("INSERT INTO metadata VALUES ('bounds', '-180.0,-85.0511,180.0,85.0511')")
                stmt.execute("INSERT INTO metadata VALUES ('center', '0.0,0.0,2')")
                stmt.execute("INSERT INTO metadata VALUES ('minzoom', '0')")
                stmt.execute("INSERT INTO metadata VALUES ('maxzoom', '5')")
                stmt.execute("INSERT INTO metadata VALUES ('version', '1.0.0')")
                stmt.execute("INSERT INTO metadata VALUES ('type', 'overlay')")
            }
            
            conn.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, ?)").use { stmt ->
                stmt.setInt(1, 0)  // zoom
                stmt.setInt(2, 0)  // column (x)
                stmt.setInt(3, 0)  // row (y)
                stmt.setBytes(4, SINGLE_PIXEL_PNG)
                stmt.executeUpdate()
            }
        }
        println("Generated: $path")
    }
    
    private fun generateRasterDem(path: Path) {
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
                stmt.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
                
                stmt.execute("INSERT INTO metadata VALUES ('format', 'png')")
                stmt.execute("INSERT INTO metadata VALUES ('name', 'Terrain RGB Demo')")
                stmt.execute("INSERT INTO metadata VALUES ('description', 'terrain dem source for auto-detection testing')")
                stmt.execute("INSERT INTO metadata VALUES ('attribution', 'Terrain Data')")
                stmt.execute("INSERT INTO metadata VALUES ('bounds', '-180.0,-85.0511,180.0,85.0511')")
                stmt.execute("INSERT INTO metadata VALUES ('center', '0.0,0.0,0')")
                stmt.execute("INSERT INTO metadata VALUES ('minzoom', '0')")
                stmt.execute("INSERT INTO metadata VALUES ('maxzoom', '12')")
            }
            
            conn.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, ?)").use { stmt ->
                stmt.setInt(1, 0)
                stmt.setInt(2, 0)
                stmt.setInt(3, 0)
                stmt.setBytes(4, SINGLE_PIXEL_PNG)
                stmt.executeUpdate()
            }
        }
        println("Generated: $path")
    }
    
    private fun generateMalformedMetadata(path: Path) {
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
                stmt.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
                
                stmt.execute("INSERT INTO metadata VALUES ('format', 'png')")
                stmt.execute("INSERT INTO metadata VALUES ('name', 'Malformed Tileset')")
                // Intentionally malformed values
                stmt.execute("INSERT INTO metadata VALUES ('bounds', 'not,a,valid,bounds')")
                stmt.execute("INSERT INTO metadata VALUES ('center', 'invalid')")
                stmt.execute("INSERT INTO metadata VALUES ('minzoom', 'abc')")
                stmt.execute("INSERT INTO metadata VALUES ('maxzoom', 'xyz')")
            }
            
            conn.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, ?)").use { stmt ->
                stmt.setInt(1, 0)
                stmt.setInt(2, 0)
                stmt.setInt(3, 0)
                stmt.setBytes(4, SINGLE_PIXEL_PNG)
                stmt.executeUpdate()
            }
        }
        println("Generated: $path")
    }
    
    private fun generateMissingMetadata(path: Path) {
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
                stmt.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
                // Only insert format, leave other metadata empty
                stmt.execute("INSERT INTO metadata VALUES ('format', 'png')")
            }
            
            conn.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, ?)").use { stmt ->
                stmt.setInt(1, 0)
                stmt.setInt(2, 0)
                stmt.setInt(3, 0)
                stmt.setBytes(4, SINGLE_PIXEL_PNG)
                stmt.executeUpdate()
            }
        }
        println("Generated: $path")
    }
}