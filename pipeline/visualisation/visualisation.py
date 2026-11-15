from __future__ import annotations

import argparse
import sys
from pathlib import Path

from pipeline.helpers import parse_generation_args
from pipeline.visualisation import collection, extraction, plotting


def run(input_dir: Path) -> bool:
    collection.run(input_dir)
    errors_file = extraction.run(collection.report_path(input_dir))
    plotting.run(errors_file)
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Collect evaluation reports, build DataFrames, and plot rates and errors."
    )
    parser.add_argument(
        "input_dir", type=Path, nargs="?",
        help="Generation date directory (default: newest Generations/date:* directory).",
    )
    args = parse_generation_args(parser, argv)
    try:
        successful = run(args.input_dir.resolve())
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Visualisation failed: {error}", file=sys.stderr)
        return 1
    if successful:
        print(
            "Wrote evaluation and category-error-rate CSVs plus passing-rate, "
            "error-count, category-error-rate, metric, and bar-chart figures in "
            f"{collection.report_path(args.input_dir).parent}",
        )
    return 0 if successful else 1


if __name__ == "__main__":
    raise SystemExit(main())
