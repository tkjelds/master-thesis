
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

from pipeline.helpers import (
    discover_model_directories,
    generation_model_name,
    parse_generation_args,
    timestamp_now,
)


def compile_file(source: Path) -> dict[str, object]:
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
    relative_model = model_dir.relative_to(input_dir)
    return (
        results_root / relative_model / "results"
        / f"{generation_model_name(model_dir)}.results.json"
    )


def write_report(
    model_dir: Path, input_dir: Path, results_root: Path, timestamp: str
) -> bool:
    records = [
        compile_file(source)
        for source in sorted(model_dir.glob("*.java"), key=lambda path: path.name)
    ]
    report = {
        "timestamp": timestamp,
        "model": generation_model_name(model_dir),
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
    parser = argparse.ArgumentParser(
        description="Compile generated Java files and write per-model JSON reports."
    )
    parser.add_argument(
        "--input-dir",
        type=Path,
        help="Generation date directory (default: newest Generations/date:* directory).",
    )
    parser.add_argument(
        "--results-dir",
        type=Path,
        help=(
            "Optional root that mirrors <client>/<model>/run-N/<category>/results/ "
            "(default: <input-dir>)."
        ),
    )
    args = parse_generation_args(parser, argv)
    return 0 if run(args.input_dir, args.results_dir) else 1


if __name__ == "__main__":
    raise SystemExit(main())
