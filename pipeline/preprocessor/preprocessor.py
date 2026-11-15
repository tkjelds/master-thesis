from __future__ import annotations

import argparse
from pathlib import Path

from pipeline.helpers import discover_model_directories, parse_generation_args
from pipeline.preprocessor.markdown_fences import (
    strip_known_response_prose,
    strip_markdown_fences,
)
from pipeline.preprocessor.thread_safe import annotate_source


def preprocess_source(source: str) -> str:
    source = strip_markdown_fences(source)
    source = strip_known_response_prose(source)
    return annotate_source(source)


def run(input_dir: Path) -> int:
    changed = 0
    for model_dir in discover_model_directories(input_dir):
        for path in sorted(model_dir.glob("*.java")):
            source = path.read_text(encoding="utf-8")
            try:
                prepared = preprocess_source(source)
            except (IndexError, ValueError) as error:
                print(f"Skipped {path}: {error}", flush=True)
                continue
            if prepared != source:
                path.write_text(prepared, encoding="utf-8")
                changed += 1
                print(f"Prepared {path}", flush=True)
    return changed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Prepare generated Java sources before compilation and analysis."
    )
    parser.add_argument(
        "input_dir", type=Path, nargs="?",
        help="Generation date directory (default: newest Generations/date:* directory).",
    )
    args = parse_generation_args(parser, argv)
    changed = run(args.input_dir)
    print(f"Preprocessed {changed} file(s).", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
