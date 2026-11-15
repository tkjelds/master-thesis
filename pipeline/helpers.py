from __future__ import annotations

import argparse
import re
from datetime import datetime
from pathlib import Path

from pipeline.prompt_generator.problems import PROBLEM_CATEGORIES

PROMPT_CATEGORIES = PROBLEM_CATEGORIES.keys()


def parse_generation_args(
    parser: argparse.ArgumentParser, argv: list[str] | None = None,
) -> argparse.Namespace:
    args = parser.parse_args(argv)
    if args.input_dir is None:
        root = Path("Generations")
        try:
            generations = [path for path in root.glob("date:*") if path.is_dir()]
        except OSError as error:
            parser.error(f"Unable to find generations in {root}: {error}")
        if not generations:
            parser.error(f"No generation directories found in {root}; supply an input directory.")
        args.input_dir = max(generations, key=lambda path: path.name)
    return args


def timestamp_now() -> str:
    return datetime.now().astimezone().strftime("date:%Y-%m-%dT%H-%M")


def is_generation_run(path: Path) -> bool:
    return re.fullmatch(r"run-[1-9][0-9]*", path.name) is not None


def discover_model_directories(input_dir: Path) -> list[Path]:
    if not input_dir.is_dir():
        raise NotADirectoryError(f"Input directory does not exist: {input_dir}")

    directories = []
    for client_dir in input_dir.iterdir():
        if not client_dir.is_dir() or client_dir.name == "results":
            continue
        for model_dir in client_dir.iterdir():
            if not model_dir.is_dir():
                continue
            runs = [
                path for path in model_dir.iterdir()
                if path.is_dir() and is_generation_run(path)
            ]
            for source_root in [model_dir, *runs]:
                categories = [
                    source_root / category
                    for category in PROMPT_CATEGORIES
                    if (source_root / category).is_dir()
                ]
                directories.extend(categories)
                if (
                    any(source_root.glob("*.java"))
                    or not categories and (source_root != model_dir or not runs)
                ):
                    directories.append(source_root)
    return sorted(directories, key=lambda path: path.relative_to(input_dir).as_posix())


def generation_model_name(source_dir: Path) -> str:
    model_dir = (
        source_dir.parent
        if source_dir.name in PROMPT_CATEGORIES
        else source_dir
    )
    if is_generation_run(model_dir):
        model_dir = model_dir.parent
    return model_dir.name


def find_latest_file(directory: Path, pattern: str = "*") -> Path:
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
