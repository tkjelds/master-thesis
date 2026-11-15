from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
import tempfile
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path

from pipeline.helpers import PROMPT_CATEGORIES, is_generation_run
from pipeline.runner.runner import MANIFEST_FILENAME

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
REPORT_TYPES = ("compilation", "codeql", "infer", "correctness", "sokrates")
CODEQL_RULE_IDS = ("java/safe-publication", "java/escaping", "java/not-threadsafe")


@dataclass(frozen=True)
class Group:
    client: str
    model: str
    category: str = ""
    run: int | None = None

    def source_path(self) -> Path:
        parts = [self.client, self.model]
        if self.run is not None:
            parts.append(f"run-{self.run}")
        if self.category:
            parts.append(self.category)
        return Path(*parts)


@dataclass(frozen=True)
class Report:
    group: Group
    tool: str
    path: Path


@dataclass
class Row:
    client: str
    model: str
    category: str
    run: int | None
    problem: str
    java_file: str
    compilation_errors: int | None = None
    compilation_status: str = "missing"
    codeql_issues: int | None = None
    codeql_status: str = "missing"
    infer_issues: int | None = None
    infer_status: str = "missing"
    correctness_probability: float | None = None
    correctness_status: str = "missing"
    sokrates_lines_of_code: int | None = None
    sokrates_unit_count: int | None = None
    sokrates_unit_size_total_loc: int | None = None
    sokrates_unit_size_max_loc: int | None = None
    sokrates_unit_size_mean_loc: float | None = None
    sokrates_conditional_complexity_total: int | None = None
    sokrates_conditional_complexity_max: int | None = None
    sokrates_conditional_complexity_mean: float | None = None
    sokrates_status: str = "missing"
    codeql_by_rule: dict[str, int] = field(default_factory=dict)
    infer_by_type: dict[str, int] = field(default_factory=dict)


def _object(value: object, context: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError(f"{context}: expected an object")
    return value


def _text(value: object, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{context}: expected a nonblank string")
    return value


def _boolean(value: object, context: str) -> bool:
    if not isinstance(value, bool):
        raise ValueError(f"{context}: expected a boolean")
    return value


def _count(value: object, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{context}: issue_count must be a nonnegative integer")
    return value


def _metric_number(value: object, context: str) -> int | float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or value < 0
        or not math.isfinite(value)
    ):
        raise ValueError(f"{context}: expected a nonnegative finite number")
    return value


def _metric_integer(value: object, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{context}: expected a nonnegative integer")
    return value


def _metric_float(value: object, context: str) -> float:
    number = _metric_number(value, context)
    if number is None:
        raise ValueError(f"{context}: expected a nonnegative finite number")
    return float(number)


def _optional_metric_integer(value: object, context: str) -> int | None:
    return None if value is None else _metric_integer(value, context)


def _optional_metric_float(value: object, context: str) -> float | None:
    return None if value is None else _metric_float(value, context)


def _issue_counts(
    record: dict[str, object], count: int, context: str, tool: str, key: str,
) -> dict[str, int]:
    issues = record.get("issues")
    if not isinstance(issues, list):
        raise ValueError(f"{context}: expected a {tool} issues list")
    if len(issues) != count:
        raise ValueError(f"{context}: {tool} issue_count disagrees with issues list")
    counts: dict[str, int] = {}
    for value in issues:
        issue = _object(value, context)
        error_type = _text(issue.get(key), f"{context}: {tool} {key}")
        counts[error_type] = counts.get(error_type, 0) + 1
    return counts


def _infer_issue_counts(
    record: dict[str, object], count: int, context: str,
) -> dict[str, int]:
    issues = record.get("issues")
    if not isinstance(issues, list):
        raise ValueError(f"{context}: expected a Infer issues list")
    if len(issues) != count:
        raise ValueError(f"{context}: Infer issue_count disagrees with issues list")
    counts: dict[str, int] = {}
    for value in issues:
        issue = _object(value, context)
        bug_type = _text(issue.get("bug_type"), f"{context}: Infer bug_type")
        error_type = bug_type
        if bug_type == "THREAD_SAFETY_VIOLATION":
            qualifier = issue.get("qualifier")
            if isinstance(qualifier, str):
                category = qualifier.strip().split(".", maxsplit=1)[0].strip()
                if category:
                    error_type = f"{bug_type}_{category}"
        counts[error_type] = counts.get(error_type, 0) + 1
    return counts


def _read_json(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise ValueError(f"Invalid JSON in {path}: {error}") from error
    return _object(value, str(path))


def _group(parts: tuple[str, ...]) -> Group:
    if not 2 <= len(parts) <= 4 or any(
        part in (".", "..") or "/" in part or "\\" in part or not part.strip()
        for part in parts
    ):
        raise ValueError(f"Invalid collection group: {parts}")
    category = ""
    run = None
    for part in parts[2:]:
        if is_generation_run(Path(part)) and run is None:
            run = int(part.removeprefix("run-"))
        elif part in PROMPT_CATEGORIES and not category and run is None:
            category = part
        else:
            raise ValueError(f"Invalid collection group: {parts}")
    return Group(parts[0], parts[1], category, run)


def _reports(document: dict[str, object]) -> list[Report]:
    reports: list[Report] = []

    def visit(node: dict[str, object], parts: tuple[str, ...]) -> None:
        group = _group(parts) if len(parts) >= 2 else None
        for key, value in node.items():
            if group is not None and key in REPORT_TYPES:
                path = Path(_text(value, f"{parts}/{key}"))
                if not path.is_absolute():
                    raise ValueError(f"Collection report path must be absolute: {path}")
                reports.append(Report(group, key, path.resolve()))
            else:
                visit(_object(value, f"Collection group {parts}/{key}"), (*parts, key))

    visit(document, ())
    if not reports:
        raise ValueError("Collection contains no report paths")
    return reports


def _generation_root(reports: list[Report]) -> Path:
    roots = set()
    for report in reports:
        source = report.path.parent.parent
        parts = report.group.source_path().parts
        if report.path.parent.name != "results" or source.parts[-len(parts):] != parts:
            raise ValueError(f"Report path disagrees with collection group: {report.path}")
        roots.add(source.parents[len(parts) - 1])
    if len(roots) != 1:
        raise ValueError("Collection report paths must belong to one generation")
    return roots.pop()


def _manifest(root: Path) -> dict[Path, Row]:
    path = root / MANIFEST_FILENAME
    document = _read_json(path)
    entries = document.get("generations")
    if not isinstance(entries, list):
        raise ValueError(f"{path}: expected a generations list")
    rows = {}
    identities = set()
    for index, value in enumerate(entries):
        context = f"{path}: entry {index}"
        entry = _object(value, context)
        relative = Path(_text(entry.get("java_file"), f"{context}: java_file"))
        if relative.is_absolute() or ".." in relative.parts or relative.suffix != ".java":
            raise ValueError(f"{context}: invalid Java file path: {relative}")
        parts = list(relative.parent.parts)
        if len(parts) == 4 and is_generation_run(Path(parts[2])):
            parts[-2], parts[-1] = parts[-1], parts[-2]
        group = _group(tuple(parts))
        problem = _text(entry.get("problem"), f"{context}: problem")
        _text(entry.get("model"), f"{context}: model")
        expected_run = f"run-{group.run}" if group.run is not None else None
        if (
            entry.get("client") != group.client
            or entry.get("category", "") != group.category
            or entry.get("run") != expected_run
        ):
            raise ValueError(f"{context}: metadata disagrees with Java file path")
        source = (root / relative).resolve()
        identity = (group, problem)
        if not source.is_relative_to(root):
            raise ValueError(f"{context}: Java file escapes generation directory")
        if source in rows or identity in identities:
            raise ValueError(f"{context}: duplicate manifest mapping for {relative}")
        identities.add(identity)
        rows[source] = Row(
            group.client, group.model, group.category, group.run,
            problem, relative.as_posix(),
        )
    if not rows:
        raise ValueError(f"{path}: no generated problems found")
    return rows


def _apply(row: Row, record: dict[str, object], report: Report) -> None:
    context = f"{report.path}: {row.java_file}"
    if report.tool == "compilation":
        compiled = _boolean(record.get("compiled"), f"{context}: compiled")
        row.compilation_errors = int(not compiled)
        row.compilation_status = "success" if compiled else "failed"
        return

    analyzed = _boolean(record.get("analyzed"), f"{context}: analyzed")
    status = "success" if analyzed else "failed"
    if report.tool == "sokrates":
        row.sokrates_status = status
        if analyzed:
            metrics = _object(record.get("metrics"), f"{context}: metrics")
            metrics_context = f"{context}: Sokrates"
            row.sokrates_lines_of_code = _metric_integer(
                metrics.get("lines_of_code"), f"{metrics_context} lines_of_code",
            )
            row.sokrates_unit_count = _metric_integer(
                metrics.get("unit_count"), f"{metrics_context} unit_count",
            )
            row.sokrates_unit_size_total_loc = _metric_integer(
                metrics.get("unit_size_total_loc"),
                f"{metrics_context} unit_size_total_loc",
            )
            row.sokrates_unit_size_max_loc = _optional_metric_integer(
                metrics.get("unit_size_max_loc"),
                f"{metrics_context} unit_size_max_loc",
            )
            row.sokrates_unit_size_mean_loc = _optional_metric_float(
                metrics.get("unit_size_mean_loc"),
                f"{metrics_context} unit_size_mean_loc",
            )
            row.sokrates_conditional_complexity_total = _metric_integer(
                metrics.get("conditional_complexity_total"),
                f"{metrics_context} conditional_complexity_total",
            )
            row.sokrates_conditional_complexity_max = _optional_metric_integer(
                metrics.get("conditional_complexity_max"),
                f"{metrics_context} conditional_complexity_max",
            )
            row.sokrates_conditional_complexity_mean = _optional_metric_float(
                metrics.get("conditional_complexity_mean"),
                f"{metrics_context} conditional_complexity_mean",
            )
        return
    if report.tool == "correctness":
        probability = record.get("probability")
        if analyzed:
            if (
                isinstance(probability, bool)
                or not isinstance(probability, (int, float))
                or not 0 <= probability <= 1
                or not math.isfinite(probability)
            ):
                raise ValueError(f"{context}: invalid correctness probability")
            row.correctness_probability = probability
        row.correctness_status = status
    else:
        count = _count(record.get("issue_count"), context) if analyzed else None
        if report.tool == "codeql":
            row.codeql_issues = count
            row.codeql_status = status
            if count is not None:
                row.codeql_by_rule = _issue_counts(
                    record, count, context, "CodeQL", "rule_id",
                )
        else:
            row.infer_issues = count
            row.infer_status = status
            if count is not None:
                row.infer_by_type = _infer_issue_counts(record, count, context)


def _load_results(reports: list[Report], rows: dict[Path, Row]) -> None:
    seen = set()
    for report in reports:
        try:
            document = _read_json(report.path)
        except FileNotFoundError:
            print(f"Warning: missing report: {report.path}", file=sys.stderr)
            continue
        records = document.get("files")
        if not isinstance(records, list):
            raise ValueError(f"{report.path}: expected a files list")
        if report.tool == "codeql":
            unmapped = document.get("unmapped_issues", [])
            if not isinstance(unmapped, list):
                raise ValueError(f"{report.path}: unmapped_issues must be a list")
            if unmapped:
                print(
                    f"Warning: {report.path}: excluding {len(unmapped)} unmapped issues "
                    "from per-problem counts",
                    file=sys.stderr,
                )
        for value in records:
            record = _object(value, str(report.path))
            filename = Path(_text(record.get("file"), f"{report.path}: file"))
            if filename.is_absolute():
                source = filename.resolve()
            elif len(filename.parts) == 1:
                source = (report.path.parent.parent / filename).resolve()
            else:
                source = (REPOSITORY_ROOT / filename).resolve()
            if source not in rows:
                raise ValueError(f"{report.path}: no manifest mapping for {filename}")
            if source.parent != report.path.parent.parent:
                raise ValueError(f"{report.path}: file belongs to another group: {filename}")
            key = (source, report.tool)
            if key in seen:
                raise ValueError(f"{report.path}: duplicate {report.tool} record: {filename}")
            seen.add(key)
            _apply(rows[source], record, report)


def run(collection_file: Path) -> Path:
    collection_file = collection_file.resolve()
    reports = _reports(_read_json(collection_file))
    rows = _manifest(_generation_root(reports))
    _load_results(reports, rows)
    ordered = sorted(rows.values(), key=lambda row: (
        row.run or 0, row.client, row.model, row.category, row.problem, row.java_file,
    ))
    for row in ordered:
        missing = [
            tool for tool in REPORT_TYPES if getattr(row, f"{tool}_status") == "missing"
        ]
        if missing:
            print(
                f"Warning: {row.java_file}: missing results: {', '.join(missing)}",
                file=sys.stderr,
            )
    output = collection_file.with_name("errors.csv")
    breakdowns = {
        "codeql": ("codeql_by_rule", CODEQL_RULE_IDS),
        "infer": ("infer_by_type", ()),
    }
    excluded = {"java_file", *(attribute for attribute, _ in breakdowns.values())}
    fieldnames = [
        item.name for item in fields(Row) if item.name not in excluded
    ]
    type_columns = {}
    used_columns = set(fieldnames)
    for tool, (attribute, defaults) in breakdowns.items():
        error_types = set(defaults)
        for row in ordered:
            error_types.update(getattr(row, attribute))
        columns = {}
        for error_type in sorted(error_types):
            name = error_type.removeprefix("java/") if tool == "codeql" else error_type
            column = tool + "_" + re.sub(r"[^a-zA-Z0-9_]", "_", name).lower()
            if column in used_columns:
                raise ValueError(
                    f"{tool} error type {error_type!r} conflicts with CSV column {column!r}"
                )
            used_columns.add(column)
            columns[error_type] = column
        type_columns[tool] = columns
        position = fieldnames.index(f"{tool}_status")
        fieldnames[position:position] = columns.values()
    with tempfile.TemporaryDirectory(prefix=".extraction-", dir=output.parent) as directory:
        staged = Path(directory) / output.name
        with staged.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fieldnames)
            writer.writeheader()
            for row in ordered:
                values = asdict(row)
                for name in excluded:
                    del values[name]
                for tool, columns in type_columns.items():
                    counts = getattr(row, breakdowns[tool][0])
                    for error_type, column in columns.items():
                        values[column] = (
                            counts.get(error_type, 0)
                            if getattr(row, f"{tool}_status") == "success" else None
                        )
                writer.writerow(values)
        staged.replace(output)
    return output


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extract per-problem counts into errors.csv beside collection.json."
    )
    parser.add_argument("collection_file", type=Path, help="Collection JSON file.")
    args = parser.parse_args(argv)
    try:
        output = run(args.collection_file)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Extraction failed: {error}", file=sys.stderr)
        return 1
    print(f"Wrote {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
