#!/usr/bin/env python3
"""
Generate committed MBTiles fixtures for upload parity testing.
Run: python3 kotlin/parity-fixtures/inputs/mbtiles/uploads/generate_fixtures.py
"""

import os
import sqlite3
import struct

# Minimal valid 1x1 PNG (grayscale, 1 pixel)
# PNG signature + IHDR chunk + IDAT chunk + IEND chunk
SINGLE_PIXEL_PNG = bytes([
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,  # PNG signature
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,  # IHDR chunk
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,  # 1x1 dimensions
    0x08, 0x00, 0x00, 0x00, 0x00,                      # 8-bit grayscale
    0x7A, 0x8F, 0x58, 0x1D,                            # IHDR CRC
    0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,   # IDAT chunk
    0x78, 0x9C, 0x63, 0x60, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
    0x5A, 0x9C, 0x3E, 0x71,                            # IDAT CRC
    0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,   # IEND chunk
    0xAE, 0x42, 0x60, 0x82                             # IEND CRC
])

OUTPUT_DIR = os.path.dirname(os.path.abspath(__file__))


def create_valid_raster(path):
    """Create a valid raster MBTiles file."""
    conn = sqlite3.connect(path)
    cursor = conn.cursor()
    
    cursor.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
    cursor.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
    
    metadata = [
        ('format', 'png'),
        ('name', 'Test Raster Tileset'),
        ('description', 'Test tileset for parity verification'),
        ('attribution', 'Test Attribution'),
        ('bounds', '-180.0,-85.0511,180.0,85.0511'),
        ('center', '0.0,0.0,2'),
        ('minzoom', '0'),
        ('maxzoom', '5'),
        ('version', '1.0.0'),
        ('type', 'overlay'),
    ]
    cursor.executemany("INSERT INTO metadata VALUES (?, ?)", metadata)
    
    cursor.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (0, 0, 0, SINGLE_PIXEL_PNG))
    
    conn.commit()
    conn.close()
    print(f"Generated: {path}")


def create_raster_dem(path):
    """Create a raster-dem MBTiles file for auto-detection testing."""
    conn = sqlite3.connect(path)
    cursor = conn.cursor()
    
    cursor.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
    cursor.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
    
    metadata = [
        ('format', 'png'),
        ('name', 'Terrain RGB Demo'),
        ('description', 'terrain dem source for auto-detection testing'),
        ('attribution', 'Terrain Data'),
        ('bounds', '-180.0,-85.0511,180.0,85.0511'),
        ('center', '0.0,0.0,0'),
        ('minzoom', '0'),
        ('maxzoom', '12'),
    ]
    cursor.executemany("INSERT INTO metadata VALUES (?, ?)", metadata)
    
    cursor.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (0, 0, 0, SINGLE_PIXEL_PNG))
    
    conn.commit()
    conn.close()
    print(f"Generated: {path}")


def create_malformed_metadata(path):
    """Create an MBTiles file with malformed metadata values."""
    conn = sqlite3.connect(path)
    cursor = conn.cursor()
    
    cursor.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
    cursor.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
    
    metadata = [
        ('format', 'png'),
        ('name', 'Malformed Tileset'),
        ('bounds', 'not,a,valid,bounds'),  # Intentionally malformed
        ('center', 'invalid'),
        ('minzoom', 'abc'),
        ('maxzoom', 'xyz'),
    ]
    cursor.executemany("INSERT INTO metadata VALUES (?, ?)", metadata)
    
    cursor.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (0, 0, 0, SINGLE_PIXEL_PNG))
    
    conn.commit()
    conn.close()
    print(f"Generated: {path}")


def create_missing_metadata(path):
    """Create an MBTiles file with minimal metadata."""
    conn = sqlite3.connect(path)
    cursor = conn.cursor()
    
    cursor.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
    cursor.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
    
    # Only insert format, leave other metadata empty
    cursor.execute("INSERT INTO metadata VALUES (?, ?)", ('format', 'png'))
    
    cursor.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (0, 0, 0, SINGLE_PIXEL_PNG))
    
    conn.commit()
    conn.close()
    print(f"Generated: {path}")


if __name__ == "__main__":
    print(f"Generating MBTiles fixtures in: {OUTPUT_DIR}")
    
    create_valid_raster(os.path.join(OUTPUT_DIR, "raster.mbtiles"))
    create_raster_dem(os.path.join(OUTPUT_DIR, "raster-dem.mbtiles"))
    create_malformed_metadata(os.path.join(OUTPUT_DIR, "malformed-metadata.mbtiles"))
    create_missing_metadata(os.path.join(OUTPUT_DIR, "missing-metadata.mbtiles"))
    
    print("All fixtures generated successfully!")
