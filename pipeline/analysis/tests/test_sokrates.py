import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from pipeline.analysis.sokrates.analyzer import (
    analyze_command,
    analyze_file,
    extract_metrics,
    report_path,
    run,
)


def fake_sokrates(failures=()):
    def fake_run(command, **kwargs):
        source_root = Path(command[command.index("-srcRoot") + 1])
        sources = sorted(source_root.rglob("*.java"))
        if len(sources) != 1:
            raise AssertionError(f"Expected one isolated Java source, got {sources}")
        source = sources[0]
        if source.name in failures:
            return subprocess.CompletedProcess(
                command, 1, stdout="", stderr="Sokrates analysis failed",
            )

        output = Path(command[command.index("-outputFolder") + 1])
        data_dir = output / "data"
        data_dir.mkdir(parents=True)
        relative = "src/main/java/" + source.name
        units = [
            {
                "relativeFileName": relative,
                "linesOfCode": size,
                "mcCabeIndex": complexity,
            }
            for size, complexity in ((10, 3), (20, 7))
        ]
        with zipfile.ZipFile(data_dir / "data.zip", "w") as archive:
            archive.writestr(
                "files.json",
                json.dumps([{"relativePath": relative, "linesOfCode": 42}]),
            )
            archive.writestr("units.json", json.dumps(units))
            archive.writestr(
                "analysisResults.json",
                json.dumps({"unitsAnalysisResults": {"totalNumberOfUnits": 2}}),
            )
        return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

    return fake_run


class SokratesAnalyzerTests(unittest.TestCase):
    def test_command_supports_an_executable_or_jar_command_without_a_shell(self):
        command = analyze_command(
            Path("/tmp/source root"),
            Path("/tmp/report output"),
            'java -jar "/tools/sokrates jar.jar"',
        )
        self.assertEqual(
            command,
            [
                "java", "-jar", "/tools/sokrates jar.jar", "analyze",
                "-srcRoot", "/tmp/source root",
                "-outputFolder", "/tmp/report output",
                "-skipGitHistory", "-dataOnly",
            ],
        )

    @patch("pipeline.analysis.sokrates.analyzer.subprocess.run")
    def test_metrics_are_extracted_from_the_isolated_sokrates_data_export(self, mocked_run):
        mocked_run.side_effect = fake_sokrates()
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Counter.java"
            source.write_text("class Counter {}", encoding="utf-8")

            record = analyze_file(source)

        self.assertTrue(record["analyzed"])
        self.assertEqual(record["error"], "")
        self.assertEqual(record["metrics"], {
            "lines_of_code": 42,
            "unit_count": 2,
            "unit_size_total_loc": 30,
            "unit_size_max_loc": 20,
            "unit_size_mean_loc": 15.0,
            "conditional_complexity_total": 10,
            "conditional_complexity_max": 7,
            "conditional_complexity_mean": 5.0,
        })
        command, = mocked_run.call_args.args
        self.assertFalse(mocked_run.call_args.kwargs["shell"])
        self.assertIn("-skipGitHistory", command)
        source_root = Path(command[command.index("-srcRoot") + 1])
        self.assertEqual(
            source_root.relative_to(Path(mocked_run.call_args.kwargs["cwd"])),
            Path("source"),
        )

    @patch("pipeline.analysis.sokrates.analyzer.subprocess.run")
    def test_runs_once_per_source_and_writes_one_report_per_tier(self, mocked_run):
        mocked_run.side_effect = fake_sokrates(failures={"Bad.java"})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "date:test"
            source_dir = root / "client" / "model" / "run-1" / "datastructures"
            source_dir.mkdir(parents=True)
            for name in ("Good.java", "Bad.java"):
                (source_dir / name).write_text(f"class {name[:-5]} {{}}", encoding="utf-8")
            original_sources = {
                path.name: path.read_bytes() for path in source_dir.glob("*.java")
            }

            self.assertFalse(run(root))
            self.assertEqual(
                {path.name: path.read_bytes() for path in source_dir.glob("*.java")},
                original_sources,
            )

            output = report_path(source_dir)
            self.assertEqual(
                output,
                source_dir / "results" / "model.sokrates.result.json",
            )
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["analysis_mode"], "isolated-source-file")
            self.assertEqual(report["model"], "model")
            self.assertEqual(
                [record["file"] for record in report["files"]],
                ["Bad.java", "Good.java"],
            )
            records = {record["file"]: record for record in report["files"]}
            self.assertFalse(records["Bad.java"]["analyzed"])
            self.assertIn("Sokrates analysis failed", records["Bad.java"]["error"])
            self.assertTrue(records["Good.java"]["analyzed"])
            self.assertEqual(mocked_run.call_count, 2)

    def test_rejects_truncated_unit_exports_instead_of_reporting_partial_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "data.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(
                    "files.json",
                    json.dumps([{
                        "relativePath": "src/main/java/Counter.java",
                        "linesOfCode": 20,
                    }]),
                )
                archive.writestr("units.json", "[]")
                archive.writestr(
                    "analysisResults.json",
                    json.dumps({"unitsAnalysisResults": {"totalNumberOfUnits": 1}}),
                )
            with self.assertRaisesRegex(ValueError, "would be incomplete"):
                extract_metrics(archive_path, "Counter.java")

    def test_file_without_extracted_units_has_null_unit_summaries(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "data.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(
                    "files.json",
                    json.dumps([{
                        "relativePath": "src/main/java/Counter.java",
                        "linesOfCode": 1,
                    }]),
                )
                archive.writestr("units.json", "[]")
                archive.writestr(
                    "analysisResults.json",
                    json.dumps({"unitsAnalysisResults": {"totalNumberOfUnits": 0}}),
                )
            metrics = extract_metrics(archive_path, "Counter.java")

        self.assertEqual(metrics, {
            "lines_of_code": 1,
            "unit_count": 0,
            "unit_size_total_loc": 0,
            "unit_size_max_loc": None,
            "unit_size_mean_loc": None,
            "conditional_complexity_total": 0,
            "conditional_complexity_max": None,
            "conditional_complexity_mean": None,
        })

    def test_report_path_uses_model_name_for_legacy_directories(self):
        model_dir = Path("generation/client/model/datastructures")
        self.assertEqual(
            report_path(model_dir),
            model_dir / "results" / "model.sokrates.result.json",
        )


if __name__ == "__main__":
    unittest.main()
