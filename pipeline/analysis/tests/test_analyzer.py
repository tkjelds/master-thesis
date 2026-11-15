import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.analysis.codeql.analyzer import (
    DEFAULT_QUERIES,
    analyze_command,
    codeql_version,
    main,
    parse_issues,
    report_path,
    run,
    sarif_path,
)
from pipeline.analysis.codeql.database_builder import database_path

RULE = {
    "id": "java/not-threadsafe",
    "name": "java/not-threadsafe",
    "shortDescription": {"text": "Not thread-safe"},
    "defaultConfiguration": {"level": "warning"},
}
ISSUE = {
    "ruleId": RULE["id"],
    "message": {"text": "This field access is not protected by any monitor."},
    "locations": [
        {
            "physicalLocation": {
                "artifactLocation": {
                    "uri": "Racy.java",
                    "uriBaseId": "%SRCROOT%",
                },
                "region": {
                    "startLine": 4,
                    "startColumn": 12,
                    "endLine": 4,
                    "endColumn": 16,
                },
            }
        }
    ],
}
SARIF = {
    "version": "2.1.0",
    "runs": [
        {
            "tool": {"driver": {"name": "CodeQL", "rules": [RULE]}},
            "artifacts": [
                {"location": {"uri": "Racy.java", "uriBaseId": "%SRCROOT%"}}
            ],
            "results": [ISSUE],
        }
    ],
}


def fake_codeql(document=SARIF, failing=(), missing_report=(), invalid_report=()):

    def fake_run(command, **kwargs):
        if command[1] == "version":
            return subprocess.CompletedProcess(
                command, 0, stdout='{"version": "2.27.1"}\n', stderr=""
            )
        model = Path(command[3]).parent.parent.name
        if model in failing:
            return subprocess.CompletedProcess(
                command,
                2,
                stdout="",
                stderr="\n".join(f"error line {line}" for line in range(60)),
            )
        if model not in missing_report:
            output = Path(
                next(
                    arg.removeprefix("--output=")
                    for arg in command
                    if arg.startswith("--output=")
                )
            )
            output.write_text(
                "invalid JSON" if model in invalid_report else json.dumps(document),
                encoding="utf-8",
            )
        return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

    return fake_run


def create_model(root, name="luna", sources=("Racy.java", "Safe.java"), database=True):
    model_dir = root / "copilot" / name
    model_dir.mkdir(parents=True)
    for source in sources:
        (model_dir / source).write_text("class X {}\n", encoding="utf-8")
    if database:
        database_path(model_dir).mkdir(parents=True)
        (database_path(model_dir) / "keep").write_text(
            "existing database", encoding="utf-8"
        )
    return model_dir


class AnalyzerTests(unittest.TestCase):
    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_analyzes_runs_and_categories_with_separate_reports(self, mocked_run):
        mocked_run.side_effect = fake_codeql()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sources = [
                root / "client" / "model" / run_name / category
                for run_name in ("run-1", "run-2")
                for category in ("datastructures", "novel_problems")
            ]
            for source_dir in sources:
                source_dir.mkdir(parents=True)
                (source_dir / "Racy.java").write_text("class Racy {}")
                database_path(source_dir).mkdir(parents=True)
            self.assertTrue(run(root))
            self.assertEqual(mocked_run.call_count, 5)
            for source_dir in sources:
                output = source_dir / "results" / "model.codeql.results.json"
                self.assertEqual(report_path(source_dir), output)
                report = json.loads(output.read_text())
                self.assertEqual(report["model"], "model")
                self.assertEqual(report["files"][0]["file"], "Racy.java")
                self.assertTrue(report["files"][0]["analyzed"])
                self.assertEqual(report["files"][0]["issue_count"], 1)
                self.assertEqual(
                    sarif_path(source_dir),
                    source_dir / "results" / "model.codeql.sarif",
                )
                self.assertTrue(sarif_path(source_dir).is_file())

    def test_paths_are_in_model_results_directory(self):
        model_dir = Path("date:x") / "copilot" / "gpt-5.6-luna"
        self.assertEqual(
            report_path(model_dir),
            model_dir / "results" / "gpt-5.6-luna.codeql.results.json",
        )
        self.assertEqual(
            sarif_path(model_dir),
            model_dir / "results" / "gpt-5.6-luna.codeql.sarif",
        )

    def test_command_analyzes_existing_database_without_building(self):
        model_dir = Path("/date:x/client/encoded%2Fmodel")
        queries = Path("/queries with spaces:and@/suite.qls")
        self.assertEqual(
            analyze_command(
                model_dir, Path("/output/report.sarif"), "/bin/codeql", queries
            ),
            [
                "/bin/codeql",
                "database",
                "analyze",
                str(database_path(model_dir)),
                f"path:{queries}",
                "--format=sarifv2.1.0",
                "--output=/output/report.sarif",
                "--no-sarif-group-rules-by-pack",
            ],
        )
        self.assertTrue(DEFAULT_QUERIES.is_file())

    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_writes_sorted_per_file_report_and_preserves_database(self, mocked_run):
        mocked_run.side_effect = fake_codeql()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "date:2026-01-01T00-00"
            model_dir = create_model(root, name="encoded%2Fmodel")
            empty = create_model(root, name="empty", sources=(), database=False)

            self.assertTrue(run(root))

            report = json.loads(report_path(model_dir).read_text(encoding="utf-8"))
            self.assertEqual(report["model"], "encoded%2Fmodel")
            self.assertEqual(report["tool"], "codeql")
            self.assertEqual(report["codeql_version"], "2.27.1")
            self.assertEqual(report["database"], str(database_path(model_dir)))
            self.assertEqual(report["queries"], str(DEFAULT_QUERIES))
            self.assertTrue(report["timestamp"].startswith("date:"))
            self.assertEqual(report["unmapped_issues"], [])
            records = {record["file"]: record for record in report["files"]}
            self.assertEqual(list(records), ["Racy.java", "Safe.java"])
            self.assertTrue(all(record["analyzed"] for record in records.values()))
            self.assertTrue(all(record["exit_code"] == 0 for record in records.values()))
            self.assertTrue(all(record["stderr"] == "" for record in records.values()))
            self.assertEqual(records["Racy.java"]["issue_count"], 1)
            self.assertEqual(
                records["Racy.java"]["issues"][0],
                {
                    "rule_id": "java/not-threadsafe",
                    "rule_name": "Not thread-safe",
                    "severity": "warning",
                    "message": ISSUE["message"]["text"],
                    "file": "Racy.java",
                    "line": 4,
                    "column": 12,
                    "end_line": 4,
                    "end_column": 16,
                },
            )
            self.assertEqual(records["Safe.java"]["issues"], [])
            self.assertEqual(records["Safe.java"]["issue_count"], 0)
            self.assertEqual(
                json.loads(sarif_path(model_dir).read_text(encoding="utf-8")), SARIF
            )
            self.assertEqual(
                (database_path(model_dir) / "keep").read_text(encoding="utf-8"),
                "existing database",
            )
            self.assertFalse((empty / "results").exists())
            self.assertEqual(mocked_run.call_count, 2)
            for call in mocked_run.call_args_list:
                self.assertFalse(call.kwargs["shell"])
                self.assertNotIn("create", call.args[0])
                self.assertNotIn("javac", call.args[0])

    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_failed_analysis_reports_stderr_tail_and_continues(self, mocked_run):
        mocked_run.side_effect = fake_codeql(failing={"a-failed"})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failed = create_model(root, name="a-failed")
            good = create_model(root, name="z-good")
            sarif_path(failed).write_text(json.dumps(SARIF), encoding="utf-8")

            self.assertFalse(run(root))

            records = json.loads(report_path(failed).read_text(encoding="utf-8"))["files"]
            for record in records:
                self.assertFalse(record["analyzed"])
                self.assertEqual(record["exit_code"], 2)
                self.assertEqual(record["issue_count"], 0)
                self.assertEqual(len(record["stderr"].splitlines()), 40)
                self.assertTrue(record["stderr"].startswith("error line 20"))
            self.assertFalse(sarif_path(failed).exists())
            self.assertTrue(report_path(good).is_file())
            self.assertTrue(database_path(failed).is_dir())

    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_missing_database_is_reported_without_creating_it(self, mocked_run):
        mocked_run.side_effect = fake_codeql()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            missing = create_model(root, name="a-missing", database=False)
            good = create_model(root, name="z-good")

            self.assertFalse(run(root))

            records = json.loads(report_path(missing).read_text(encoding="utf-8"))["files"]
            self.assertTrue(all(not record["analyzed"] for record in records))
            self.assertTrue(all(record["exit_code"] is None for record in records))
            self.assertIn("database_builder", records[0]["stderr"])
            self.assertFalse(database_path(missing).exists())
            self.assertTrue(report_path(good).is_file())
            self.assertEqual(mocked_run.call_count, 2)

    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_missing_or_invalid_sarif_is_failure_even_with_zero_exit(self, mocked_run):
        mocked_run.side_effect = fake_codeql(
            missing_report={"a-missing"}, invalid_report={"b-invalid"}
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            missing = create_model(root, name="a-missing")
            invalid = create_model(root, name="b-invalid")
            good = create_model(root, name="z-good")

            self.assertFalse(run(root))

            for model_dir in (missing, invalid):
                record = json.loads(
                    report_path(model_dir).read_text(encoding="utf-8")
                )["files"][0]
                self.assertFalse(record["analyzed"])
                self.assertEqual(record["exit_code"], 0)
                self.assertTrue(record["stderr"])
            self.assertTrue(sarif_path(invalid).is_file())
            self.assertTrue(report_path(good).is_file())

    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_missing_executable_still_writes_failed_records(self, mocked_run):
        mocked_run.side_effect = FileNotFoundError("CodeQL executable not found")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = create_model(root)

            self.assertFalse(run(root, codeql="/missing/codeql"))

            report = json.loads(report_path(model_dir).read_text(encoding="utf-8"))
            self.assertIsNone(report["codeql_version"])
            self.assertFalse(report["files"][0]["analyzed"])
            self.assertIn("executable not found", report["files"][0]["stderr"])

    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_no_findings_is_success(self, mocked_run):
        document = copy.deepcopy(SARIF)
        document["runs"][0]["results"] = []
        mocked_run.side_effect = fake_codeql(document)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = create_model(root)
            self.assertTrue(run(root))
            records = json.loads(
                report_path(model_dir).read_text(encoding="utf-8")
            )["files"]
            self.assertTrue(all(record["issue_count"] == 0 for record in records))

    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_unmapped_and_unlocated_findings_are_not_discarded(self, mocked_run):
        document = copy.deepcopy(SARIF)
        outside = copy.deepcopy(ISSUE)
        outside["locations"][0]["physicalLocation"]["artifactLocation"] = {
            "uri": "nested/Racy.java"
        }
        unlocated = {**ISSUE, "locations": []}
        document["runs"][0]["results"] = [outside, unlocated]
        mocked_run.side_effect = fake_codeql(document)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = create_model(root)
            self.assertTrue(run(root))
            report = json.loads(report_path(model_dir).read_text(encoding="utf-8"))
            self.assertEqual(
                [issue["file"] for issue in report["unmapped_issues"]],
                ["nested/Racy.java", None],
            )
            self.assertTrue(
                all(record["issue_count"] == 0 for record in report["files"])
            )

    def test_parser_resolves_artifact_and_rule_indexes_and_encoded_source_root(self):
        document = copy.deepcopy(SARIF)
        sarif_run = document["runs"][0]
        sarif_run["originalUriBaseIds"] = {
            "%SRCROOT%": {"uri": "file:///old/date%3Ax/client/model%2Fid/"}
        }
        sarif_run["artifacts"][0]["location"]["uri"] = "Racy%20queue.java"
        result = sarif_run["results"][0]
        result.pop("ruleId")
        result["ruleIndex"] = 0
        result["locations"][0]["physicalLocation"]["artifactLocation"] = {"index": 0}
        self.assertEqual(self.parse(document)[0]["file"], "Racy queue.java")
        self.assertEqual(self.parse(document)[0]["rule_id"], RULE["id"])

    def test_parser_resolves_absolute_file_uri(self):
        with tempfile.TemporaryDirectory() as directory:
            model_dir = Path(directory)
            document = copy.deepcopy(SARIF)
            document["runs"][0]["results"][0]["locations"][0]["physicalLocation"][
                "artifactLocation"
            ] = {"uri": (model_dir / "Racy.java").as_uri()}
            report = model_dir / "input.sarif"
            report.write_text(json.dumps(document), encoding="utf-8")
            self.assertEqual(parse_issues(report, model_dir)[0]["file"], "Racy.java")

    def test_parser_rejects_invalid_sarif_structure(self):
        for document in (
            {},
            {"version": "2.1.0", "runs": []},
            {"version": "2.1.0", "runs": [None]},
        ):
            with self.subTest(document=document), self.assertRaises(ValueError):
                self.parse(document)

    def test_parser_rejects_invalid_artifact_index(self):
        document = copy.deepcopy(SARIF)
        document["runs"][0]["results"][0]["locations"][0]["physicalLocation"][
            "artifactLocation"
        ] = {"index": 10}
        with self.assertRaisesRegex(ValueError, "artifact index"):
            self.parse(document)

    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_version_failure_is_logged(self, mocked_run):
        mocked_run.return_value = subprocess.CompletedProcess(
            ["codeql", "version"], 0, stdout="{}", stderr=""
        )
        with patch("pipeline.analysis.codeql.analyzer.sys.stderr") as stderr:
            self.assertIsNone(codeql_version())
        self.assertTrue(stderr.write.called)

    @patch("pipeline.analysis.codeql.analyzer.run")
    def test_cli_passes_executable_and_query_override_and_exit_status(self, mocked_run):
        mocked_run.return_value = False
        self.assertEqual(
            main(["date:x", "--codeql", "/bin/codeql", "--queries", "custom.qls"]),
            1,
        )
        mocked_run.assert_called_once_with(
            Path("date:x").resolve(), "/bin/codeql", Path("custom.qls")
        )

    @patch("pipeline.analysis.codeql.analyzer.subprocess.run")
    def test_missing_input_and_query_paths_are_rejected_before_execution(self, mocked_run):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(NotADirectoryError):
                run(root / "missing")
            with self.assertRaises(FileNotFoundError):
                run(root, queries=root / "missing.qls")
        mocked_run.assert_not_called()

    def parse(self, document):
        with tempfile.TemporaryDirectory() as directory:
            model_dir = Path(directory)
            report = model_dir / "input.sarif"
            report.write_text(json.dumps(document), encoding="utf-8")
            return parse_issues(report, model_dir)


if __name__ == "__main__":
    unittest.main()
