
"""Compile generated Java files and write per-model JSON reports."""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

from pipeline.helpers import timestamp_now

DEFAULT_INPUT_DIR = Path("/data")


def discover_model_directories(input_dir: Path) -> list[Path]:
    """Find client/model directories directly below a date-root input."""
    if not input_dir.is_dir():
        raise NotADirectoryError(f"Input directory does not exist: {input_dir}")

    return sorted(
        (
            model_dir
            for client_dir in input_dir.iterdir()
            if client_dir.is_dir() and client_dir.name != "results"
            for model_dir in client_dir.iterdir()
            if model_dir.is_dir()
        ),
        key=lambda path: path.relative_to(input_dir).as_posix(),
    )


def compile_file(source: Path) -> dict[str, object]:
    """Compile one Java source file in an isolated temporary output directory."""
    with tempfile.TemporaryDirectory(prefix="javac-") as classes_dir:
        try:
            completed = subprocess.run(
                ["javac", "-d", classes_dir, str(source)],
                capture_output=True,
                text=True,
                check=False,
                shell=False,
            )
        except OSError as error:
            raise RuntimeError("Unable to execute javac") from error

    return {
        "file": source.name,
        "compiled": completed.returncode == 0,
        "exit_code": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    }


def report_path(model_dir: Path, input_dir: Path, results_root: Path) -> Path:
    """Return ``<results_root>/<client>/<model>/results/<model>.results.json``."""
    relative_model = model_dir.relative_to(input_dir)
    return results_root / relative_model / "results" / f"{model_dir.name}.results.json"


def write_report(
    model_dir: Path, input_dir: Path, results_root: Path, timestamp: str
) -> bool:
    """Compile a model directory and write its JSON report."""
    records = [
        compile_file(source)
        for source in sorted(model_dir.glob("*.java"), key=lambda path: path.name)
    ]
    report = {
        "timestamp": timestamp,
        "model": model_dir.name,
        "files": records,
    }
    output = report_path(model_dir, input_dir, results_root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return all(record["compiled"] for record in records)


def run(input_dir: Path, results_dir: Path | None = None) -> bool:
    """Compile all model directories and return whether every file succeeded."""
    results_root = results_dir or input_dir
    timestamp = timestamp_now()
    model_dirs = discover_model_directories(input_dir)
    successful = True
    for model_dir in model_dirs:
        successful = (
            write_report(model_dir, input_dir, results_root, timestamp)
            and successful
        )
    return successful


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input-dir",
        type=Path,
        default=DEFAULT_INPUT_DIR,
        help=f"Mounted date directory to scan (default: {DEFAULT_INPUT_DIR}).",
    )
    parser.add_argument(
        "--results-dir",
        type=Path,
        help=(
            "Optional root that mirrors <client>/<model>/results/ "
            "(default: <input-dir>)."
        ),
    )
    args = parser.parse_args(argv)
    return 0 if run(args.input_dir, args.results_dir) else 1


if __name__ == "__main__":
    raise SystemExit(main())
