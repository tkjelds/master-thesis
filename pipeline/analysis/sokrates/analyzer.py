from __future__ import annotations

import argparse
import json
import math
import shlex
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import cast

from pipeline.helpers import (
    discover_model_directories,
    generation_model_name,
    parse_generation_args,
    timestamp_now,
)

STDERR_TAIL_LINES = 40
SOURCE_SUBPATH = Path("src/main/java")


def report_path(model_dir: Path) -> Path:
    return model_dir / "results" / f"{generation_model_name(model_dir)}.sokrates.result.json"


def analyze_command(
    source_root: Path, output_dir: Path, sokrates: str = "sokrates",
) -> list[str]:
    command = shlex.split(sokrates)
    if not command:
        raise ValueError("Sokrates command must not be empty")
    return [
        *command,
        "analyze",
        "-srcRoot",
        str(source_root),
        "-outputFolder",
        str(output_dir),
        "-skipGitHistory",
        "-dataOnly",
    ]


def _object(value: object, context: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError(f"{context}: expected an object")
    return cast(dict[str, object], value)


def _array(value: object, context: str) -> list[object]:
    if not isinstance(value, list):
        raise ValueError(f"{context}: expected an array")
    return value


def _integer(value: object, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{context}: expected a nonnegative integer")
    return value


def _read_json(archive: zipfile.ZipFile, name: str) -> object:
    try:
        content = archive.read(name)
    except KeyError as error:
        raise ValueError(f"Sokrates data export is missing {name}") from error
    try:
        return json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Invalid JSON in Sokrates export {name}: {error}") from error


def _relative_file(record: dict[str, object], field: str, context: str) -> str:
    value = record.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError(f"{context}: expected a nonblank {field}")
    return value.replace("\\", "/").removeprefix("/")


def extract_metrics(data_zip: Path, source_name: str) -> dict[str, int | float | None]:
    expected_path = (SOURCE_SUBPATH / source_name).as_posix()
    with zipfile.ZipFile(data_zip) as archive:
        file_records = [
            _object(value, "Sokrates files.json record")
            for value in _array(_read_json(archive, "files.json"), "Sokrates files.json")
        ]
        matching_files = [
            record for record in file_records
            if _relative_file(record, "relativePath", "Sokrates file record") == expected_path
        ]
        if len(matching_files) != 1:
            raise ValueError(
                f"Expected exactly one Sokrates file record for {expected_path}, "
                f"found {len(matching_files)}"
            )
        lines_of_code = _integer(
            matching_files[0].get("linesOfCode"), f"{expected_path}: linesOfCode",
        )

        units = [
            _object(value, "Sokrates units.json record")
            for value in _array(_read_json(archive, "units.json"), "Sokrates units.json")
        ]
        file_units = [
            unit for unit in units
            if _relative_file(unit, "relativeFileName", "Sokrates unit record") == expected_path
        ]
        if len(file_units) != len(units):
            raise ValueError("Isolated Sokrates analysis returned units for another file")

        analysis_results = _object(
            _read_json(archive, "analysisResults.json"),
            "Sokrates analysisResults.json",
        )
        units_results = _object(
            analysis_results.get("unitsAnalysisResults"),
            "Sokrates unitsAnalysisResults",
        )
        total_units = _integer(
            units_results.get("totalNumberOfUnits"),
            "Sokrates totalNumberOfUnits",
        )
        if total_units != len(file_units):
            raise ValueError(
                f"Sokrates exported {len(file_units)} of {total_units} units; "
                "per-file metrics would be incomplete"
            )

    sizes = [
        _integer(unit.get("linesOfCode"), f"{expected_path}: unit linesOfCode")
        for unit in file_units
    ]
    complexities = [
        _integer(unit.get("mcCabeIndex"), f"{expected_path}: unit mcCabeIndex")
        for unit in file_units
    ]
    count = len(file_units)
    return {
        "lines_of_code": lines_of_code,
        "unit_count": count,
        "unit_size_total_loc": sum(sizes),
        "unit_size_max_loc": max(sizes) if sizes else None,
        "unit_size_mean_loc": math.fsum(sizes) / count if sizes else None,
        "conditional_complexity_total": sum(complexities),
        "conditional_complexity_max": max(complexities) if complexities else None,
        "conditional_complexity_mean": math.fsum(complexities) / count if complexities else None,
    }


def _tail(text: str, lines: int = STDERR_TAIL_LINES) -> str:
    return "\n".join(text.splitlines()[-lines:])


def analyze_file(
    source: Path, sokrates: str = "sokrates",
) -> dict[str, object]:
    record: dict[str, object] = {
        "file": source.name,
        "analyzed": False,
        "metrics": None,
        "error": "",
    }
    with tempfile.TemporaryDirectory(prefix="sokrates-file-") as directory:
        workspace = Path(directory)
        source_root = workspace / "source"
        isolated_source = source_root / SOURCE_SUBPATH / source.name
        try:
            isolated_source.parent.mkdir(parents=True)
            shutil.copy2(source, isolated_source)
        except OSError as error:
            record["error"] = str(error)
            return record
        output_dir = workspace / "reports"
        try:
            command = analyze_command(source_root, output_dir, sokrates)
            completed = subprocess.run(
                command,
                cwd=workspace,
                capture_output=True,
                text=True,
                check=False,
                shell=False,
            )
        except (OSError, ValueError) as error:
            record["error"] = str(error)
            return record

        if completed.returncode != 0:
            record["error"] = _tail(completed.stderr or completed.stdout) or (
                f"Sokrates exited with status {completed.returncode}"
            )
            return record

        data_zip = output_dir / "data" / "data.zip"
        if not data_zip.is_file():
            record["error"] = f"Sokrates did not create its data export: {data_zip}"
            return record
        try:
            metrics = extract_metrics(data_zip, source.name)
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            record["error"] = str(error)
            return record

        record.update(analyzed=True, metrics=metrics)
    return record


def run(input_dir: Path, sokrates: str = "sokrates") -> bool:
    model_dirs = discover_model_directories(input_dir)
    timestamp = timestamp_now()
    successful = True
    for model_dir in model_dirs:
        sources = sorted(model_dir.glob("*.java"), key=lambda path: path.name)
        if not sources:
            continue
        records = []
        for source in sources:
            print(f"Sokrates: {source.relative_to(input_dir)}", flush=True)
            record = analyze_file(source, sokrates)
            records.append(record)
            if not record["analyzed"]:
                print(
                    f"Sokrates failed for {source}: {record['error']}",
                    file=sys.stderr,
                )
                successful = False

        output = report_path(model_dir)
        report = {
            "timestamp": timestamp,
            "model": generation_model_name(model_dir),
            "tool": "sokrates",
            "analysis_mode": "isolated-source-file",
            "files": records,
        }
        try:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(
                json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n",
                encoding="utf-8",
            )
        except OSError as error:
            print(f"Unable to write Sokrates report {output}: {error}", file=sys.stderr)
            successful = False
            continue
        print(f"Wrote {output}", flush=True)
    return successful


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extract isolated per-file Sokrates metrics from generated Java sources."
    )
    parser.add_argument(
        "input_dir",
        type=Path,
        nargs="?",
        help="Generation date directory (default: newest Generations/date:* directory).",
    )
    parser.add_argument(
        "--sokrates",
        default="sokrates",
        help='Sokrates command (default: sokrates; e.g. "java -jar /path/sokrates.jar").',
    )
    args = parse_generation_args(parser, argv)
    try:
        return 0 if run(args.input_dir.resolve(), args.sokrates) else 1
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Sokrates analysis failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
