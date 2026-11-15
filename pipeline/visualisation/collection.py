from __future__ import annotations

import argparse
import json
import stat
import sys
from pathlib import Path

from pipeline.analysis.codeql.analyzer import report_path as codeql_report_path
from pipeline.analysis.correctness.analyzer import report_path as correctness_report_path
from pipeline.analysis.infer.racerd import report_path as infer_report_path
from pipeline.analysis.sokrates.analyzer import report_path as sokrates_report_path
from pipeline.helpers import (
    discover_model_directories,
    is_generation_run,
    parse_generation_args,
)
from pipeline.verifier.java_verifier import report_path as compilation_report_path

DEFAULT_OUTPUT_DIR = Path(__file__).resolve().parents[2] / "visualisation"

type Collection = dict[str, Collection | Path]


def report_path(input_dir: Path) -> Path:
    return DEFAULT_OUTPUT_DIR / input_dir.resolve().name / "collection.json"


def run(input_dir: Path) -> Collection:
    input_dir = input_dir.resolve()
    if not stat.S_ISDIR(input_dir.stat().st_mode):
        raise NotADirectoryError(f"Input is not a directory: {input_dir}")

    collected: Collection = {}
    for source_dir in discover_model_directories(input_dir):
        group = source_dir.relative_to(input_dir).as_posix()
        candidates = {
            "compilation": compilation_report_path(source_dir, input_dir, input_dir),
            "codeql": codeql_report_path(source_dir),
            "infer": infer_report_path(source_dir),
            "correctness": correctness_report_path(source_dir),
            "sokrates": sokrates_report_path(source_dir),
        }
        reports = {}
        missing = []
        for report_type, path in candidates.items():
            try:
                mode = path.stat().st_mode
            except FileNotFoundError:
                missing.append(report_type)
                continue
            if stat.S_ISREG(mode):
                reports[report_type] = path
            else:
                missing.append(report_type)
        if missing:
            print(
                f"Warning: {group}: missing reports: {', '.join(missing)}",
                file=sys.stderr,
            )
        if reports:
            parts = list(source_dir.relative_to(input_dir).parts)
            if len(parts) == 4 and is_generation_run(source_dir.parent):
                parts[-2], parts[-1] = parts[-1], parts[-2]
            branch = collected
            for part in parts:
                child = branch.setdefault(part, {})
                if not isinstance(child, dict):
                    raise ValueError(f"Conflicting collection group: {group}")
                branch = child
            branch.update(reports)

    if not collected:
        raise FileNotFoundError(f"No result reports found in {input_dir}")
    output = report_path(input_dir)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(collected, indent=2, default=str) + "\n", encoding="utf-8",
    )
    return collected


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Collect report paths and save visualisation/<generation>/collection.json."
    )
    parser.add_argument(
        "input_dir", type=Path, nargs="?",
        help="Generation date directory (default: newest Generations/date:* directory).",
    )
    args = parse_generation_args(parser, argv)
    try:
        collected = run(args.input_dir)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Visualisation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(collected, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
