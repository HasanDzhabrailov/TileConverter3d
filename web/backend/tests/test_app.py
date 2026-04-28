import sqlite3
import time
from pathlib import Path

import app.jobs as jobs_module

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


PNG_BYTES = b"\x89PNG\r\n\x1a\nmock"


def _create_client(tmp_path: Path) -> TestClient:
    app = create_app(Settings(storage_root=tmp_path / "data", frontend_dist=tmp_path / "dist"))
    return TestClient(app)


def _build_mbtiles_bytes(tmp_path: Path) -> bytes:
    mbtiles_path = tmp_path / "sample.mbtiles"
    connection = sqlite3.connect(mbtiles_path)
    try:
        connection.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
        connection.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        connection.execute("INSERT INTO metadata (name, value) VALUES ('format', 'png')")
        connection.execute("INSERT INTO metadata (name, value) VALUES ('name', 'Sample Tileset')")
        connection.execute("INSERT INTO metadata (name, value) VALUES ('attribution', 'Sample attribution')")
        connection.execute("INSERT INTO metadata (name, value) VALUES ('bounds', '10,20,11,21')")
        connection.execute("INSERT INTO metadata (name, value) VALUES ('center', '10.5,20.5,4')")
        connection.execute("INSERT INTO metadata (name, value) VALUES ('minzoom', '4')")
        connection.execute("INSERT INTO metadata (name, value) VALUES ('maxzoom', '12')")
        connection.execute(
            "INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)",
            (0, 0, 0, PNG_BYTES),
        )
        connection.commit()
    finally:
        connection.close()
    return mbtiles_path.read_bytes()


def _wait_for_status(client: TestClient, job_id: str, expected_status: str, timeout: float = 3.0) -> dict[str, object]:
    deadline = time.time() + timeout
    while time.time() < deadline:
        response = client.get(f"/api/jobs/{job_id}")
        assert response.status_code == 200
        job = response.json()
        if job["status"] == expected_status:
            return job
        time.sleep(0.05)
    raise AssertionError(f"job {job_id} did not reach status {expected_status}")


def test_health_endpoint(tmp_path: Path):
    client = _create_client(tmp_path)

    response = client.get("/api/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_jobs_list_starts_empty(tmp_path: Path):
    client = _create_client(tmp_path)

    response = client.get("/api/jobs")

    assert response.status_code == 200
    assert response.json() == []


def test_server_info_returns_mobile_and_localhost_addresses(tmp_path: Path):
    client = _create_client(tmp_path)

    response = client.get("/api/server-info")

    assert response.status_code == 200
    payload = response.json()
    assert payload["addresses"][0]["id"] == "mobile"
    assert payload["addresses"][1]["id"] == "localhost"


def test_create_job_saves_uploads_and_manual_bbox(tmp_path: Path, monkeypatch):
    client = _create_client(tmp_path)
    started: list[tuple[str, str]] = []

    def fake_start_job(job_id: str, base_url: str) -> None:
        started.append((job_id, base_url))

    monkeypatch.setattr(client.app.state.jobs, "start_job", fake_start_job)

    response = client.post(
        "/api/jobs",
        data={
            "bbox_mode": "manual",
            "west": "10",
            "south": "20",
            "east": "11",
            "north": "21",
            "minzoom": "9",
            "maxzoom": "10",
            "tile_size": "512",
            "scheme": "tms",
            "encoding": "mapbox",
        },
        files=[
            ("hgt_files", ("N20E010.hgt", b"fake-hgt", "application/octet-stream")),
            ("base_mbtiles", ("base.mbtiles", b"fake-mbtiles", "application/octet-stream")),
        ],
    )

    assert response.status_code == 200
    payload = response.json()
    job_id = payload["id"]
    paths = client.app.state.storage.paths_for(job_id)

    assert payload["status"] == "pending"
    assert payload["has_base_mbtiles"] is True
    assert payload["options"]["bbox"] == {"west": 10.0, "south": 20.0, "east": 11.0, "north": 21.0}
    assert payload["options"]["tile_size"] == 512
    assert payload["options"]["scheme"] == "tms"
    assert paths.uploads.joinpath("N20E010.hgt").read_bytes() == b"fake-hgt"
    assert paths.base_mbtiles.read_bytes() == b"fake-mbtiles"
    assert started == [(job_id, "http://testserver")]


def test_job_completes_and_serves_artifacts(tmp_path: Path, monkeypatch):
    client = _create_client(tmp_path)

    def fake_run_conversion(*, paths, log, **kwargs):
        terrain_tile = paths.terrain_root / "8" / "0" / "0.png"
        terrain_tile.parent.mkdir(parents=True, exist_ok=True)
        terrain_tile.write_bytes(PNG_BYTES)
        paths.terrain_mbtiles.write_bytes(b"mock-mbtiles")
        paths.tilejson.write_text('{"ok": true}\n', encoding="utf-8")
        paths.stylejson.write_text('{"version": 8}\n', encoding="utf-8")
        log("conversion finished")
        return {
            "bounds": {"west": 10.0, "south": 20.0, "east": 11.0, "north": 21.0},
            "tile_count": 1,
        }

    monkeypatch.setattr(jobs_module, "run_conversion", fake_run_conversion)

    response = client.post(
        "/api/jobs",
        files=[("hgt_files", ("N20E010.hgt", b"fake-hgt", "application/octet-stream"))],
    )

    assert response.status_code == 200
    job_id = response.json()["id"]
    job = _wait_for_status(client, job_id, "completed")

    assert job["result"]["tile_count"] == 1
    assert job["artifacts"] == {
        "terrain_mbtiles": f"/api/jobs/{job_id}/downloads/terrain-rgb.mbtiles",
        "tilejson": f"/api/jobs/{job_id}/downloads/tiles.json",
        "stylejson": f"/api/jobs/{job_id}/downloads/style.json",
        "terrain_tile_url_template": f"/api/jobs/{job_id}/terrain/{{z}}/{{x}}/{{y}}.png",
        "public_terrain_tile_url_template": f"http://testserver/api/jobs/{job_id}/terrain/{{z}}/{{x}}/{{y}}.png",
        "public_tilejson": f"http://testserver/api/jobs/{job_id}/tilejson",
        "public_stylejson": f"http://testserver/api/jobs/{job_id}/style",
    }
    assert client.get(f"/api/jobs/{job_id}/downloads/terrain-rgb.mbtiles").status_code == 200
    assert client.get(f"/api/jobs/{job_id}/tilejson").status_code == 200
    assert client.get(f"/api/jobs/{job_id}/style").status_code == 200
    tile_response = client.get(f"/api/jobs/{job_id}/terrain/8/0/0.png")
    assert tile_response.status_code == 200
    assert tile_response.headers["content-type"] == "image/png"


def test_job_failure_updates_status_and_logs(tmp_path: Path, monkeypatch):
    client = _create_client(tmp_path)

    def fake_run_conversion(**kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(jobs_module, "run_conversion", fake_run_conversion)

    response = client.post(
        "/api/jobs",
        files=[("hgt_files", ("N20E010.hgt", b"fake-hgt", "application/octet-stream"))],
    )

    assert response.status_code == 200
    job_id = response.json()["id"]
    job = _wait_for_status(client, job_id, "failed")
    logs_response = client.get(f"/api/jobs/{job_id}/logs")

    assert job["error"] == "boom"
    assert logs_response.status_code == 200
    assert any("ERROR: boom" in line for line in logs_response.json()["logs"])


def test_upload_mbtiles_tileset_and_serve_tile(tmp_path: Path):
    client = _create_client(tmp_path)
    mbtiles_bytes = _build_mbtiles_bytes(tmp_path)

    response = client.post(
        "/api/mbtiles",
        data={"source_type": "raster"},
        files=[("mbtiles", ("sample.mbtiles", mbtiles_bytes, "application/octet-stream"))],
    )

    assert response.status_code == 200
    payload = response.json()
    tileset_id = payload["id"]

    assert payload["filename"] == "sample.mbtiles"
    assert payload["tile_url_template"] == f"/api/mbtiles/{tileset_id}/{{z}}/{{x}}/{{y}}.png"
    assert payload["public_tile_url_template"].endswith(f"/api/mbtiles/{tileset_id}/{{z}}/{{x}}/{{y}}.png")
    assert payload["tilejson_url"] == f"/api/mbtiles/{tileset_id}/tilejson"
    assert payload["public_tilejson_url"].endswith(f"/api/mbtiles/{tileset_id}/tilejson")
    assert payload["style_url"] == f"/api/mbtiles/{tileset_id}/style"
    assert payload["public_style_url"].endswith(f"/api/mbtiles/{tileset_id}/style")
    assert payload["mobile_style_url"] == f"/api/mbtiles/{tileset_id}/style-mobile"
    assert payload["public_mobile_style_url"].endswith(f"/api/mbtiles/{tileset_id}/style-mobile")
    assert payload["name"] == "Sample Tileset"
    assert payload["attribution"] == "Sample attribution"
    assert payload["source_type"] == "raster"
    assert payload["tile_format"] == "png"
    assert payload["minzoom"] == 4
    assert payload["maxzoom"] == 12
    assert payload["bounds"] == {"west": 10.0, "south": 20.0, "east": 11.0, "north": 21.0}
    assert payload["view"] == {"center_lon": 10.5, "center_lat": 20.5, "zoom": 4}
    assert client.get("/api/mbtiles").json()[0]["id"] == tileset_id
    assert client.get(f"/api/mbtiles/{tileset_id}").json()["id"] == tileset_id
    assert client.get(f"/api/mbtiles/{tileset_id}/metadata").json()["name"] == "Sample Tileset"
    tilejson_response = client.get(f"/api/mbtiles/{tileset_id}/tilejson")
    assert tilejson_response.status_code == 200
    assert tilejson_response.json()["tiles"][0].endswith(f"/api/mbtiles/{tileset_id}/{{z}}/{{x}}/{{y}}.png")
    style_response = client.get(f"/api/mbtiles/{tileset_id}/style")
    assert style_response.status_code == 200
    assert style_response.json()["sources"]["tileset"]["tiles"][0].endswith(f"/api/mbtiles/{tileset_id}/{{z}}/{{x}}/{{y}}.png")
    mobile_style_response = client.get(f"/api/mbtiles/{tileset_id}/style-mobile")
    assert mobile_style_response.status_code == 200
    assert mobile_style_response.json()["layers"][0]["id"] == "osm-base-layer"

    tile_response = client.get(f"/api/mbtiles/{tileset_id}/0/0/0.png")
    assert tile_response.status_code == 200
    assert tile_response.content == PNG_BYTES
    assert tile_response.headers["content-type"] == "image/png"


def test_upload_terrain_mbtiles_marks_raster_dem(tmp_path: Path):
    client = _create_client(tmp_path)
    mbtiles_bytes = _build_mbtiles_bytes(tmp_path)

    response = client.post(
        "/api/mbtiles",
        data={"source_type": "auto"},
        files=[("mbtiles", ("terrain-rgb.mbtiles", mbtiles_bytes, "application/octet-stream"))],
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["source_type"] == "raster-dem"
    style_response = client.get(f"/api/mbtiles/{payload['id']}/style")
    assert style_response.status_code == 200
    style = style_response.json()
    assert style["terrain"]["source"] == "tileset"
    assert style["sources"]["osm-base"]["tiles"][0].startswith("https://a.tile.openstreetmap.org/")
    assert style["layers"][0]["id"] == "osm-base-layer"
    mobile_style_response = client.get(f"/api/mbtiles/{payload['id']}/style-mobile")
    assert mobile_style_response.status_code == 200
    mobile_style = mobile_style_response.json()
    assert "terrain" not in mobile_style
    assert mobile_style["layers"][1]["id"] == "terrain-hillshade"


def test_upload_mbtiles_tileset_allows_manual_source_type(tmp_path: Path):
    client = _create_client(tmp_path)
    mbtiles_bytes = _build_mbtiles_bytes(tmp_path)

    response = client.post(
        "/api/mbtiles",
        data={"source_type": "raster-dem"},
        files=[("mbtiles", ("sample.mbtiles", mbtiles_bytes, "application/octet-stream"))],
    )

    assert response.status_code == 200
    assert response.json()["source_type"] == "raster-dem"
