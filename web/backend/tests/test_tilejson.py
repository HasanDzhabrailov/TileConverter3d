from pathlib import Path

from app.models import BBox, JobOptions
from app.tilejson import write_job_documents


def test_write_job_documents_uses_relative_urls(tmp_path: Path):
    tilejson_path = tmp_path / "tiles.json"
    stylejson_path = tmp_path / "style.json"

    write_job_documents(
        job_id="job123",
        options=JobOptions(),
        bounds=BBox(west=10.0, south=20.0, east=11.0, north=21.0),
        tilejson_path=tilejson_path,
        stylejson_path=stylejson_path,
        has_base_mbtiles=True,
    )

    tilejson_text = tilejson_path.read_text(encoding="utf-8")
    stylejson_text = stylejson_path.read_text(encoding="utf-8")

    assert '"/api/jobs/job123/terrain/{z}/{x}/{y}.png"' in tilejson_text
    assert '"/api/jobs/job123/terrain/{z}/{x}/{y}.png"' in stylejson_text
    assert '"/api/jobs/job123/base/{z}/{x}/{y}"' in stylejson_text
