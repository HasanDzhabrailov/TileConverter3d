"""Preflight validation for conversion inputs."""

from __future__ import annotations

from pathlib import Path

from .hgt import infer_hgt_size, parse_hgt_coordinate


def validate_inputs(paths: list[str | Path]) -> list[Path]:
    if not paths:
        raise ValueError("no HGT inputs were provided")
    discovered: list[Path] = []
    seen_coords: set[tuple[int, int]] = set()
    seen_sizes: set[int] = set()
    for raw_path in paths:
        path = Path(raw_path)
        if path.is_dir():
            discovered.extend(sorted(candidate for candidate in path.rglob("*") if candidate.is_file() and candidate.suffix.lower() == ".hgt"))
        elif path.is_file():
            discovered.append(path)
        else:
            raise ValueError(f"input path does not exist: {path}")
    if not discovered:
        raise ValueError("no .hgt files found in inputs")
    unique_paths: list[Path] = []
    seen_paths: set[Path] = set()
    for path in discovered:
        if path in seen_paths:
            continue
        seen_paths.add(path)
        coordinate = parse_hgt_coordinate(path)
        key = (coordinate.lat, coordinate.lon)
        if key in seen_coords:
            raise ValueError(f"duplicate HGT tile for coordinate {key}: {path}")
        seen_coords.add(key)
        seen_sizes.add(infer_hgt_size(path))
        unique_paths.append(path)
    if len(seen_sizes) > 1:
        raise ValueError("mixed HGT resolutions are not supported in one run")
    return unique_paths


def validate_zoom_range(min_zoom: int, max_zoom: int) -> None:
    if min_zoom < 0:
        raise ValueError("min zoom must be >= 0")
    if max_zoom < min_zoom:
        raise ValueError("max zoom must be >= min zoom")
