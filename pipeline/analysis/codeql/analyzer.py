from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit

from pipeline.analysis.codeql.database_builder import database_path
from pipeline.helpers import (
    discover_model_directories,
    generation_model_name,
    parse_generation_args,
    timestamp_now,
)

DEFAULT_QUERIES = Path(__file__).resolve().parent / "queries" / "concurrency.qls"
STDERR_TAIL_LINES = 40


def report_path(model_dir: Path) -> Path:
    return model_dir / "results" / f"{generation_model_name(model_dir)}.codeql.results.json"


def sarif_path(model_dir: Path) -> Path:
    return model_dir / "results" / f"{generation_model_name(model_dir)}.codeql.sarif"


def analyze_command(
    model_dir: Path,
    output: Path,
    codeql: str = "codeql",
    queries: Path = DEFAULT_QUERIES,
) -> list[str]:
    return [
        codeql,
        "database",
        "analyze",
        str(database_path(model_dir)),
        f"path:{queries}",
        "--format=sarifv2.1.0",
        f"--output={output}",
        "--no-sarif-group-rules-by-pack",
    ]


def _tail(text: str) -> str:
    return "\n".join(text.splitlines()[-STDERR_TAIL_LINES:])


def codeql_version(codeql: str = "codeql") -> str | None:
    try:
        completed = subprocess.run(
            [codeql, "version", "--format=json"],
            capture_output=True,
            text=True,
            check=False,
            shell=False,
        )
        if completed.returncode != 0:
            raise ValueError(_tail(completed.stderr or completed.stdout))
        document = _object(json.loads(completed.stdout))
        version = document.get("version")
        if not isinstance(version, str) or not version:
            raise ValueError("Version output does not contain a version string")
        return version
    except (OSError, ValueError) as error:
        print(f"Unable to read CodeQL version: {error}", file=sys.stderr)
        return None


def _object(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object in CodeQL output")
    return value


def _objects(value: object) -> list[dict[str, object]]:
    if not isinstance(value, list):
        raise ValueError("Expected a JSON array in CodeQL output")
    return [_object(item) for item in value]


def _location_file(
    location: dict[str, object], sarif_run: dict[str, object], model_dir: Path
) -> str | None:
    index = location.get("index")
    if index is not None:
        artifacts = _objects(sarif_run.get("artifacts", []))
        if not isinstance(index, int) or not 0 <= index < len(artifacts):
            raise ValueError(f"Invalid SARIF artifact index: {index}")
        location = {
            **_object(artifacts[index].get("location", {})),
            **location,
        }
    uri = location.get("uri")
    if uri is None:
        return None
    if not isinstance(uri, str):
        raise ValueError("SARIF artifact URI must be a string")

    bases = _object(sarif_run.get("originalUriBaseIds", {}))
    base_id = location.get("uriBaseId")
    if base_id is not None:
        if not isinstance(base_id, str):
            raise ValueError(f"Unknown SARIF URI base: {base_id}")
        if base_id in bases:
            base_uri = _object(bases[base_id]).get("uri")
            if not isinstance(base_uri, str):
                raise ValueError(f"Missing URI for SARIF base: {base_id}")
            uri = urljoin(base_uri, uri)
        elif base_id != "%SRCROOT%":
            raise ValueError(f"Unknown SARIF URI base: {base_id}")

    parsed = urlsplit(uri)
    if parsed.scheme not in ("", "file"):
        return uri
    path = Path(unquote(parsed.path))
    if not path.is_absolute():
        return path.as_posix()

    roots = [model_dir.absolute(), model_dir.resolve()]
    source_base = _object(bases.get("%SRCROOT%", {})).get("uri")
    if isinstance(source_base, str):
        roots.append(Path(unquote(urlsplit(source_base).path)))
    for root in roots:
        if path.is_relative_to(root):
            return path.relative_to(root).as_posix()
    return path.as_posix()


def parse_issues(report_file: Path, model_dir: Path) -> list[dict[str, object]]:
    document = _object(json.loads(report_file.read_text(encoding="utf-8")))
    if document.get("version") != "2.1.0" or "runs" not in document:
        raise ValueError("CodeQL output is not a SARIF 2.1.0 report")
    runs = _objects(document["runs"])
    if not runs:
        raise ValueError("CodeQL SARIF report contains no runs")

    issues = []
    for sarif_run in runs:
        driver = _object(_object(sarif_run.get("tool", {})).get("driver", {}))
        rules = _objects(driver.get("rules", []))
        rules_by_id = {}
        for rule in rules:
            rule_id = rule.get("id")
            if not isinstance(rule_id, str):
                raise ValueError("SARIF rule ID must be a string")
            rules_by_id[rule_id] = rule
        for result in _objects(sarif_run.get("results", [])):
            rule_id = result.get("ruleId")
            rule_index = result.get("ruleIndex")
            if rule_id is None and rule_index is not None:
                if not isinstance(rule_index, int) or not 0 <= rule_index < len(rules):
                    raise ValueError(f"Invalid SARIF rule index: {rule_index}")
                rule_id = rules[rule_index].get("id")
            if not isinstance(rule_id, str):
                raise ValueError("SARIF result must identify a rule")
            rule = rules_by_id.get(rule_id, {})
            message = _object(result.get("message", {}))
            message_text = message.get("text", message.get("markdown"))
            if not isinstance(message_text, str):
                raise ValueError("SARIF result must contain a message")
            issue = {
                "rule_id": rule_id,
                "rule_name": _object(rule.get("shortDescription", {})).get(
                    "text", rule.get("name")
                ),
                "severity": result.get(
                    "level", _object(rule.get("defaultConfiguration", {})).get("level")
                ),
                "message": message_text,
            }
            locations = _objects(result.get("locations", []))
            if not locations:
                locations = [{}]
            for location in locations:
                physical = _object(location.get("physicalLocation", {}))
                region = _object(physical.get("region", {}))
                issues.append(
                    {
                        **issue,
                        "file": _location_file(
                            _object(physical.get("artifactLocation", {})),
                            sarif_run,
                            model_dir,
                        ),
                        "line": region.get("startLine"),
                        "column": region.get("startColumn"),
                        "end_line": region.get("endLine"),
                        "end_column": region.get("endColumn"),
                    }
                )
    return issues


def write_report(
    model_dir: Path,
    timestamp: str,
    version: str | None,
    codeql: str = "codeql",
    queries: Path = DEFAULT_QUERIES,
) -> bool:
    output = report_path(model_dir)
    output.parent.mkdir(parents=True, exist_ok=True)
    raw_output = sarif_path(model_dir)
    raw_output.unlink(missing_ok=True)
    analyzed = False
    exit_code = None
    error_message = ""
    issues: list[dict[str, object]] = []

    if not database_path(model_dir).is_dir():
        error_message = (
            f"CodeQL database not found: {database_path(model_dir)}. "
            "Run pipeline.analysis.codeql.database_builder first."
        )
    else:
        with tempfile.TemporaryDirectory(prefix="codeql-analysis-") as workspace:
            raw_report = Path(workspace) / "report.sarif"
            try:
                completed = subprocess.run(
                    analyze_command(model_dir, raw_report, codeql, queries),
                    capture_output=True,
                    text=True,
                    check=False,
                    shell=False,
                )
                exit_code = completed.returncode
                if raw_report.is_file():
                    shutil.copy2(raw_report, raw_output)
                if exit_code != 0:
                    error_message = (
                        _tail(completed.stderr or completed.stdout)
                        or f"CodeQL analysis exited with status {exit_code}"
                    )
                elif not raw_report.is_file():
                    error_message = "CodeQL did not produce a SARIF report"
                else:
                    issues = parse_issues(raw_report, model_dir)
                    analyzed = True
            except (OSError, ValueError) as error:
                error_message = f"CodeQL analysis failed: {error}"

    files = []
    issues_by_file: dict[str, list[dict[str, object]]] = {
        source.name: []
        for source in sorted(model_dir.glob("*.java"), key=lambda path: path.name)
    }
    unmapped_issues = []
    for issue in issues:
        filename = issue["file"]
        if isinstance(filename, str) and filename in issues_by_file:
            issues_by_file[filename].append(issue)
        else:
            unmapped_issues.append(issue)
    for filename, file_issues in issues_by_file.items():
        files.append(
            {
                "file": filename,
                "analyzed": analyzed,
                "exit_code": exit_code,
                "issue_count": len(file_issues),
                "issues": file_issues,
                "stderr": error_message,
            }
        )

    report = {
        "timestamp": timestamp,
        "model": generation_model_name(model_dir),
        "tool": "codeql",
        "codeql_version": version,
        "database": str(database_path(model_dir)),
        "queries": str(queries),
        "files": files,
        "unmapped_issues": unmapped_issues,
    }
    output.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    if not analyzed:
        print(f"Failed to analyze {model_dir}: {error_message}", file=sys.stderr)
    return analyzed


def run(
    input_dir: Path, codeql: str = "codeql", queries: Path = DEFAULT_QUERIES
) -> bool:
    model_dirs = discover_model_directories(input_dir)
    queries = queries.resolve()
    if not queries.exists():
        raise FileNotFoundError(f"CodeQL queries not found: {queries}")
    timestamp = timestamp_now()
    version = codeql_version(codeql)
    successful = True
    for model_dir in model_dirs:
        label = model_dir.relative_to(input_dir).as_posix()
        if not any(model_dir.glob("*.java")):
            print(f"Skipping {label}: no Java files", file=sys.stderr)
            continue
        if write_report(model_dir, timestamp, version, codeql, queries):
            print(f"Wrote {report_path(model_dir)}")
        else:
            print(f"Wrote {report_path(model_dir)} (analysis failed)", file=sys.stderr)
            successful = False
    return successful


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run CodeQL concurrency queries on existing databases and write per-model JSON."
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
        "--queries",
        type=Path,
        default=DEFAULT_QUERIES,
        help="Alert query file, directory, or suite (default: bundled concurrency suite).",
    )
    args = parse_generation_args(parser, argv)
    return 0 if run(args.input_dir.resolve(), args.codeql, args.queries) else 1


if __name__ == "__main__":
    raise SystemExit(main())
