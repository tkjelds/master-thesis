from __future__ import annotations

import argparse
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

from pipeline.helpers import (
    discover_model_directories,
    generation_model_name,
    parse_generation_args,
)

BUILD_SCRIPT = Path(__file__).resolve().parent / "build.sh"


def database_path(model_dir: Path) -> Path:
    return model_dir / "results" / f"{generation_model_name(model_dir)}.codeql-db"


def create_command(
    model_dir: Path, codeql: str = "codeql", overwrite: bool = False
) -> list[str]:
    command = [
        codeql,
        "database",
        "create",
        str(database_path(model_dir)),
        "--language=java-kotlin",
        "--build-mode=manual",
        f"--command=sh {shlex.quote(str(BUILD_SCRIPT))}",
        f"--source-root={model_dir}",
    ]
    if overwrite:
        command.append("--overwrite")
    return command


def create_database(
    model_dir: Path, codeql: str = "codeql", overwrite: bool = False
) -> bool:
    database = database_path(model_dir)
    database.parent.mkdir(parents=True, exist_ok=True)
    existed = database.exists()
    try:
        completed = subprocess.run(
            create_command(model_dir, codeql, overwrite),
            check=False,
            shell=False,
        )
    except OSError as error:
        raise RuntimeError(f"Unable to execute {codeql!r}") from error
    if completed.returncode == 0:
        return True
    if overwrite or not existed:
        shutil.rmtree(database, ignore_errors=True)
    return False


def run(input_dir: Path, codeql: str = "codeql", overwrite: bool = False) -> bool:
    successful = True
    for model_dir in discover_model_directories(input_dir):
        label = model_dir.relative_to(input_dir).as_posix()
        if not any(model_dir.glob("*.java")):
            print(f"Skipping {label}: no Java files", file=sys.stderr)
            continue
        if create_database(model_dir, codeql, overwrite):
            print(f"Created {database_path(model_dir)}")
        else:
            print(f"Failed to create database for {label}", file=sys.stderr)
            successful = False
    return successful


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Create one CodeQL Java database per model/category in a generation."
    )
    parser.add_argument(
        "input_dir",
        type=Path,
        nargs="?",
        help="Generation date directory (default: newest Generations/date:* directory).",
    )
    parser.add_argument(
        "--codeql",
        default="codeql",
        help="CodeQL CLI executable (default: codeql on PATH).",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Replace existing databases.",
    )
    args = parse_generation_args(parser, argv)
    return 0 if run(args.input_dir.resolve(), args.codeql, args.overwrite) else 1


if __name__ == "__main__":
    raise SystemExit(main())
