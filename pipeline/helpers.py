"""Reusable filesystem helpers for the Python pipeline."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path


def timestamp_now() -> str:
    """Return the current local timestamp used by generated paths and reports."""
    return datetime.now().astimezone().strftime("date:%Y-%m-%dT%H-%M")


def find_latest_file(directory: Path, pattern: str = "*") -> Path:
    """Return the newest matching file in a directory."""
    files = [
        path
        for path in directory.glob(pattern)
        if path.is_file()
    ]
    if not files:
        raise FileNotFoundError(
            f"No files matching {pattern!r} found in {directory}"
        )
    return max(files, key=lambda path: path.stat().st_mtime_ns)
