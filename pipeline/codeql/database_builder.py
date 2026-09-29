"""Create one CodeQL Java database per client/model in a generation date directory."""

from __future__ import annotations

import argparse
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

from pipeline.verifier.java_verifier import discover_model_directories

BUILD_SCRIPT = Path(__file__).resolve().parent / "build.sh"


def database_path(model_dir: Path) -> Path:
    """Return ``<client>/<model>/results/<model>.codeql-db``."""
    return model_dir / "results" / f"{model_dir.name}.codeql-db"


def create_command(
    model_dir: Path, codeql: str = "codeql", overwrite: bool = False
) -> list[str]:
    """Build the ``codeql database create`` argument list for one model directory."""
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
    """Create the CodeQL database for one model directory."""
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
        # A failed create leaves a half-initialized database that blocks reruns.
        shutil.rmtree(database, ignore_errors=True)
    return False


def run(input_dir: Path, codeql: str = "codeql", overwrite: bool = False) -> bool:
    """Create databases for all model directories with Java files."""
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "input_dir",
        type=Path,
        help="Generation date directory, e.g. Generations/date:2026-09-21T20-02.",
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
    args = parser.parse_args(argv)
    return 0 if run(args.input_dir.resolve(), args.codeql, args.overwrite) else 1


if __name__ == "__main__":
    raise SystemExit(main())
