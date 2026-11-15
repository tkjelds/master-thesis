import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.analysis.infer.racerd import (
    analyze_command,
    analyze_file,
    infer_out_path,
    report_path,
    run,
)

RACE_ISSUE = {
    "bug_type": "THREAD_SAFETY_VIOLATION",
    "bug_type_hum": "Thread Safety Violation",
    "category": "Concurrency",
    "severity": "WARNING",
    "qualifier": "Read/Write race.",
    "procedure": "Racy.get():int",
    "file": "Racy.java",
    "line": 4,
    "column": -1,
    "key": "Racy.java|get|THREAD_SAFETY_VIOLATION",
    "hash": "abc",
    "bug_trace": [{"line_number": 4}],
}

STARVATION_ISSUE = {
    **RACE_ISSUE,
    "bug_type": "DEADLOCK",
    "bug_type_hum": "Deadlock",
    "qualifier": "Potential lock-order inversion.",
    "key": "Racy.java|get|DEADLOCK",
    "hash": "def",
}


def fake_infer(issues_by_file, failing=()):

    def fake_run(command, **kwargs):
        class Completed:
            stdout = "Infer version v1.3.0\n"
            stderr = ""
            returncode = 0

        completed = Completed()
        if command[1] == "--version":
            return completed
        source = command[-1]
        if source in failing:
            completed.returncode = 1
            completed.stderr = "*** Infer needs a working compilation command to run."
            return completed
        results_dir = Path(command[command.index("--results-dir") + 1])
        results_dir.mkdir(parents=True, exist_ok=True)
        (results_dir / "report.json").write_text(
            json.dumps(issues_by_file.get(source, [])), encoding="utf-8"
        )
        return completed

    return fake_run


class RacerdTests(unittest.TestCase):
    @patch("pipeline.analysis.infer.racerd.subprocess.run")
    def test_analyzes_runs_and_categories_with_separate_reports(self, mocked_run):
        mocked_run.side_effect = fake_infer({"Racy.java": [RACE_ISSUE]})
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
            self.assertTrue(run(root))
            self.assertEqual(mocked_run.call_count, 5)
            for source_dir in sources:
                output = source_dir / "results" / "model.infer.results.json"
                self.assertEqual(report_path(source_dir), output)
                report = json.loads(output.read_text())
                self.assertEqual(report["model"], "model")
                self.assertTrue(report["files"][0]["analyzed"])
                self.assertEqual(report["files"][0]["issue_count"], 1)
                kept = source_dir / "results" / "model.infer-out" / "Racy"
                self.assertEqual(infer_out_path(source_dir, Path("Racy.java")), kept)
                self.assertTrue((kept / "report.json").is_file())

    @patch("pipeline.analysis.infer.racerd.subprocess.run")
    def test_registers_nested_thread_safe_annotations_as_aliases(self, mocked_run):
        mocked_run.side_effect = fake_infer({})
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory)
            source = model / "Counter.java"
            source.write_text(
                "@Counter.ThreadSafe public class Counter {"
                " @interface ThreadSafe {} }",
                encoding="utf-8",
            )
            self.assertTrue(analyze_file(model, source)["analyzed"])
        command = mocked_run.call_args.args[0]
        self.assertEqual(
            json.loads(command[command.index("--threadsafe-aliases") + 1]),
            ["Counter$ThreadSafe"],
        )

    def test_paths_are_in_model_results_directory(self):
        model_dir = Path("date:x") / "copilot" / "gpt-5.6-luna"

        self.assertEqual(
            report_path(model_dir),
            model_dir / "results" / "gpt-5.6-luna.infer.results.json",
        )
        self.assertEqual(
            infer_out_path(model_dir, Path("Racy.java")),
            model_dir / "results" / "gpt-5.6-luna.infer-out" / "Racy",
        )

    def test_command_runs_only_racerd_and_starvation_over_javac(self):
        command = analyze_command(
            Path("/m/linked queue.java"), Path("/out"), Path("/classes")
        )

        self.assertEqual(
            command,
            [
                "infer",
                "run",
                "--no-default-checkers",
                "--racerd",
                "--starvation",
                "--results-dir",
                "/out",
                "--",
                "javac",
                "-d",
                "/classes",
                "linked queue.java",
            ],
        )

    @patch("pipeline.analysis.infer.racerd.subprocess.run")
    def test_writes_report_with_issues_and_failures(self, mocked_run):
        mocked_run.side_effect = fake_infer(
            {"Racy.java": [RACE_ISSUE, STARVATION_ISSUE]}, failing={"Bad.java"}
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "date:2026-01-01T00-00"
            model_dir = root / "copilot" / "luna"
            model_dir.mkdir(parents=True)
            for name in ("Racy.java", "Safe.java", "Bad.java"):
                (model_dir / name).write_text("class X {}\n", encoding="utf-8")
            (root / "copilot" / "empty").mkdir()

            self.assertFalse(run(root))

            report = json.loads(report_path(model_dir).read_text(encoding="utf-8"))
            self.assertEqual(report["model"], "luna")
            self.assertEqual(report["tool"], "infer")
            self.assertEqual(report["checkers"], ["racerd", "starvation"])
            self.assertEqual(report["infer_version"], "Infer version v1.3.0")
            records = {record["file"]: record for record in report["files"]}
            self.assertEqual(list(records), ["Bad.java", "Racy.java", "Safe.java"])

            racy = records["Racy.java"]
            self.assertTrue(racy["analyzed"])
            self.assertEqual(racy["issue_count"], 2)
            self.assertEqual(racy["issues"][0]["bug_type"], "THREAD_SAFETY_VIOLATION")
            self.assertEqual(racy["issues"][1]["bug_type"], "DEADLOCK")
            self.assertNotIn("bug_trace", racy["issues"][0])

            self.assertTrue(records["Safe.java"]["analyzed"])
            self.assertEqual(records["Safe.java"]["issues"], [])

            bad = records["Bad.java"]
            self.assertFalse(bad["analyzed"])
            self.assertEqual(bad["exit_code"], 1)
            self.assertIn("compilation", bad["stderr"])

            self.assertTrue(
                (infer_out_path(model_dir, Path("Racy.java")) / "report.json").is_file()
            )
            self.assertFalse((root / "copilot" / "empty" / "results").exists())
            for call in mocked_run.call_args_list:
                self.assertFalse(call.kwargs["shell"])
                if "cwd" in call.kwargs:
                    self.assertNotIn(":", str(call.kwargs["cwd"]))
                    results_dir = call.args[0][call.args[0].index("--results-dir") + 1]
                    self.assertNotIn(":", results_dir)

    @patch("pipeline.analysis.infer.racerd.subprocess.run")
    def test_findings_alone_do_not_fail_the_run(self, mocked_run):
        mocked_run.side_effect = fake_infer(
            {"Racy.java": [RACE_ISSUE, STARVATION_ISSUE]}
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = root / "copilot" / "luna"
            model_dir.mkdir(parents=True)
            (model_dir / "Racy.java").write_text("class X {}\n", encoding="utf-8")

            self.assertTrue(run(root))

    def test_missing_input_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(NotADirectoryError):
                run(Path(directory) / "missing", infer="/nonexistent/infer")


if __name__ == "__main__":
    unittest.main()
