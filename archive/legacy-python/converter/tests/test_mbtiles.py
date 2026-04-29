import sqlite3

from terrain_converter.mbtiles import MBTilesWriter, xyz_to_tms_row


def test_xyz_to_tms_row():
    assert xyz_to_tms_row(3, 2) == 5


def test_mbtiles_writer_stores_metadata_and_tile(tmp_path):
    mbtiles_path = tmp_path / "terrain.mbtiles"
    with MBTilesWriter(mbtiles_path) as writer:
        writer.write_metadata({"format": "png", "name": "terrain"})
        writer.write_tile(2, 1, 2, b"png-bytes")

    connection = sqlite3.connect(mbtiles_path)
    try:
        metadata = dict(connection.execute("SELECT name, value FROM metadata"))
        row = connection.execute(
            "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles"
        ).fetchone()
    finally:
        connection.close()

    assert metadata["format"] == "png"
    assert row == (2, 1, 1, b"png-bytes")
