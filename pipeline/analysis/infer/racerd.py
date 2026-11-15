from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from pipeline.helpers import (
    discover_model_directories,
    generation_model_name,
    parse_generation_args,
    timestamp_now,
)
from pipeline.preprocessor.thread_safe import thread_safe_aliases

CHECKERS = ["racerd", "starvation"]
ISSUE_FIELDS = (
    "bug_type",
    "bug_type_hum",
    "category",
    "severity",
    "qualifier",
    "procedure",
    "file",
    "line",
    "column",
    "key",
    "hash",
)
STDERR_TAIL_LINES = 40


def report_path(model_dir: Path) -> Path:
    return model_dir / "results" / f"{generation_model_name(model_dir)}.infer.results.json"


def infer_out_path(model_dir: Path, source: Path) -> Path:
    return (
        model_dir / "results" / f"{generation_model_name(model_dir)}.infer-out"
        / source.stem
    )


def analyze_command(
    source: Path, results_dir: Path, classes_dir: Path, infer: str = "infer",
    aliases: list[str] | None = None,
) -> list[str]:
    return [
        infer,
        "run",
        "--no-default-checkers",
        "--racerd",
        "--starvation",
        *(["--threadsafe-aliases", json.dumps(aliases)] if aliases else []),
        "--results-dir",
        str(results_dir),
        "--",
        "javac",
        "-d",
        str(classes_dir),
        source.name,
    ]


def infer_version(infer: str = "infer") -> str | None:
    try:
        completed = subprocess.run(
            [infer, "--version"],
            capture_output=True,
            text=True,
            check=False,
            shell=False,
        )
    except OSError:
        return None
    lines = completed.stdout.strip().splitlines()
    return lines[0] if completed.returncode == 0 and lines else None


def parse_issues(report_file: Path) -> list[dict[str, object]]:
    raw_issues = json.loads(report_file.read_text(encoding="utf-8"))
    return [
        {field: issue.get(field) for field in ISSUE_FIELDS}
        for issue in raw_issues
    ]


def _tail(text: str, lines: int = STDERR_TAIL_LINES) -> str:
    return "\n".join(text.splitlines()[-lines:])


def analyze_file(
    model_dir: Path, source: Path, infer: str = "infer"
) -> dict[str, object]:
    kept_dir = infer_out_path(model_dir, source)
    with tempfile.TemporaryDirectory(prefix="infer-racerd-") as workspace:
        work = Path(workspace)
        shutil.copy2(source, work / source.name)
        results_dir = work / "infer-out"
        try:
            completed = subprocess.run(
                analyze_command(
                    source, results_dir, work / "classes", infer,
                    aliases=thread_safe_aliases(source.read_text(encoding="utf-8")),
                ),
                cwd=work,
                capture_output=True,
                text=True,
                check=False,
                shell=False,
            )
        except OSError as error:
            raise RuntimeError(f"Unable to execute {infer!r}") from error

        report_file = results_dir / "report.json"
        analyzed = completed.returncode == 0 and report_file.is_file()
        issues = parse_issues(report_file) if analyzed else []
        shutil.rmtree(kept_dir, ignore_errors=True)
        if results_dir.is_dir():
            kept_dir.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(results_dir, kept_dir)

    return {
        "file": source.name,
        "analyzed": analyzed,
        "exit_code": completed.returncode,
        "issue_count": len(issues),
        "issues": issues,
        "stderr": "" if analyzed else _tail(completed.stderr or completed.stdout or ""),
    }


def write_report(
    model_dir: Path, timestamp: str, version: str | None, infer: str = "infer"
) -> bool:
    records = [
        analyze_file(model_dir, source, infer)
        for source in sorted(model_dir.glob("*.java"), key=lambda path: path.name)
    ]
    report = {
        "timestamp": timestamp,
        "model": generation_model_name(model_dir),
        "tool": "infer",
        "infer_version": version,
        "checkers": CHECKERS,
        "files": records,
    }
    output = report_path(model_dir)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return all(record["analyzed"] for record in records)


def run(input_dir: Path, infer: str = "infer") -> bool:
    model_dirs = discover_model_directories(input_dir)
    timestamp = timestamp_now()
    version = infer_version(infer)
    successful = True
    for model_dir in model_dirs:
        label = model_dir.relative_to(input_dir).as_posix()
        if not any(model_dir.glob("*.java")):
            print(f"Skipping {label}: no Java files", file=sys.stderr)
            continue
        if write_report(model_dir, timestamp, version, infer):
            print(f"Wrote {report_path(model_dir)}")
        else:
            print(
                f"Wrote {report_path(model_dir)} (some files failed to analyze)",
                file=sys.stderr,
            )
            successful = False
    return successful


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run Infer's RacerD and starvation checkers and write per-model JSON reports."
    )
    parser.add_argument(
        "input_dir",
        type=Path,
        nargs="?",
        help="Generation date directory (default: newest Generations/date:* directory).",
    )
    parser.add_argument(
        "--infer",
        default="infer",
        help="Infer executable (default: infer on PATH).",
    )
    args = parse_generation_args(parser, argv)
    return 0 if run(args.input_dir.resolve(), args.infer) else 1


if __name__ == "__main__":
    raise SystemExit(main())
