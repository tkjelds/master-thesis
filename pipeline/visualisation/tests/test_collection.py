import io
import json
import tempfile
import unittest
from contextlib import chdir, redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from pipeline.visualisation.collection import main, run


class CollectionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.generation = self.root / "date:test"
        self.generation.mkdir()
        self.output_dir = self.root / "visualisation"
        self.enterContext(patch(
            "pipeline.visualisation.collection.DEFAULT_OUTPUT_DIR", self.output_dir,
        ))

    def write_reports(self, group, types=None):
        suffixes = {
            "compilation": ".results.json",
            "codeql": ".codeql.results.json",
            "infer": ".infer.results.json",
            "sokrates": ".sokrates.result.json",
            "correctness": ".correctness.results.json",
        }
        source = self.generation / group
        model = Path(group).parts[1]
        results = source / "results"
        results.mkdir(parents=True, exist_ok=True)
        reports = {}
        for report_type in suffixes if types is None else types:
            path = results / f"{model}{suffixes[report_type]}"
            path.write_text("not parsed as JSON", encoding="utf-8")
            reports[report_type] = path
        return reports

    def test_collects_distinct_sorted_groups_without_reading_reports(self):
        groups = [
            "client-b/model/run-2/novel_problems",
            "client-a/model/run-2/datastructures",
            "client-a/model/run-1/novel_problems",
            "client-a/other-model/run-1/datastructures",
            "client-a/model/run-1/datastructures",
        ]
        reports = [self.write_reports(group) for group in groups]
        expected = {
            "client-a": {
                "model": {
                    "datastructures": {"run-1": reports[4], "run-2": reports[1]},
                    "novel_problems": {"run-1": reports[2]},
                },
                "other-model": {"datastructures": {"run-1": reports[3]}},
            },
            "client-b": {"model": {"novel_problems": {"run-2": reports[0]}}},
        }
        with (
            patch.object(Path, "read_text", side_effect=AssertionError("parsed report")),
            redirect_stderr(io.StringIO()) as errors,
        ):
            collected = run(self.generation)
        self.assertEqual(collected, expected)
        self.assertEqual(list(collected), ["client-a", "client-b"])
        self.assertEqual(errors.getvalue(), "")

    def test_preserves_legacy_and_category_only_layouts(self):
        groups = ["client/legacy", "client/categorised/datastructures", "client/runs/run-1"]
        reports = [self.write_reports(group) for group in groups]
        expected = {"client": {
            "legacy": reports[0],
            "categorised": {"datastructures": reports[1]},
            "runs": {"run-1": reports[2]},
        }}
        self.assertEqual(run(self.generation), expected)

    def test_legacy_and_numbered_runs_share_a_category_without_losing_reports(self):
        legacy = self.write_reports("client/model/datastructures")
        repeated = self.write_reports("client/model/run-1/datastructures")
        self.assertEqual(run(self.generation), {
            "client": {"model": {"datastructures": {**legacy, "run-1": repeated}}},
        })

    def test_resolves_relative_generation_input(self):
        group = "client/model/run-1/datastructures"
        reports = self.write_reports(group)
        with chdir(self.root):
            self.assertEqual(run(Path("date:test")), {
                "client": {"model": {"datastructures": {"run-1": reports}}},
            })
        self.assertTrue((self.output_dir / "date:test/collection.json").is_file())

    def test_saves_json_report_in_generation_named_directory_and_refreshes_it(self):
        group = "client/model/run-1/datastructures"
        reports = self.write_reports(group)
        run(self.generation)
        output = self.output_dir / self.generation.name / "collection.json"
        self.assertEqual(
            json.loads(output.read_text(encoding="utf-8")),
            {"client": {"model": {"datastructures": {"run-1": {
                name: str(path) for name, path in reports.items()
            }}}}},
        )

        reports.pop("correctness").unlink()
        with redirect_stderr(io.StringIO()):
            run(self.generation)
        self.assertEqual(
            json.loads(output.read_text(encoding="utf-8")),
            {"client": {"model": {"datastructures": {"run-1": {
                name: str(path) for name, path in reports.items()
            }}}}},
        )

    def test_generations_with_distinct_suffixes_keep_separate_reports(self):
        group = "client/model/run-1/datastructures"
        self.generation = self.root / "date:2026-10-28T09-37-first"
        self.write_reports(group)
        run(self.generation)
        first = self.output_dir / self.generation.name / "collection.json"
        contents = first.read_bytes()

        self.generation = self.root / "date:2026-10-28T09-37-second"
        self.write_reports(group)
        run(self.generation)
        second = self.output_dir / self.generation.name / "collection.json"
        self.assertEqual(first.read_bytes(), contents)
        self.assertTrue(second.is_file())
        self.assertNotEqual(second.read_bytes(), contents)

    def test_partial_reports_warn_and_empty_groups_are_omitted(self):
        group = "client/model/run-1/datastructures"
        reports = self.write_reports(group, ["compilation", "infer"])
        empty_group = "client/model/run-2/datastructures"
        (self.generation / empty_group).mkdir(parents=True)
        with redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(run(self.generation), {
                "client": {"model": {"datastructures": {"run-1": reports}}},
            })
        self.assertIn(
            f"{group}: missing reports: codeql, correctness, sokrates",
            errors.getvalue(),
        )
        self.assertIn(
            f"{empty_group}: missing reports: compilation, codeql, infer, correctness, sokrates",
            errors.getvalue(),
        )

    def test_ignores_manifests_raw_outputs_unrelated_files_and_report_directories(self):
        group = "client/model/run-1/datastructures"
        reports = self.write_reports(group, ["compilation"])
        source = self.generation / group
        artifacts = [
            source / "Example.java",
            source / "results/manifest.json",
            source / "results/model.codeql.sarif",
            source / "results/other.results.json",
            source / "results/model.infer-out/report.json",
            source / "results/model.infer-out/nested/results/model.results.json",
            source.parent / "results/manifest.json",
            self.generation / "results/client/model/results/model.results.json",
        ]
        for artifact in artifacts:
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_text("ignored", encoding="utf-8")
        (source / "results/model.codeql.results.json").mkdir()
        with redirect_stderr(io.StringIO()):
            self.assertEqual(run(self.generation), {
                "client": {"model": {"datastructures": {"run-1": reports}}},
            })

    def test_no_reports_raise_even_when_manifests_exist(self):
        for with_manifest in (False, True):
            with self.subTest(with_manifest=with_manifest):
                if with_manifest:
                    manifest = self.generation / "client/model/run-1/results/manifest.json"
                    manifest.parent.mkdir(parents=True)
                    manifest.write_text("{}", encoding="utf-8")
                with redirect_stderr(io.StringIO()), self.assertRaisesRegex(
                    FileNotFoundError, "No result reports found",
                ):
                    run(self.generation)
        self.assertFalse(self.output_dir.exists())

    def test_rejects_missing_input_and_file_input(self):
        with self.assertRaises(FileNotFoundError):
            run(self.root / "missing")
        file = self.root / "file"
        file.write_text("not a directory", encoding="utf-8")
        with self.assertRaises(NotADirectoryError):
            run(file)

    def test_propagates_directory_and_report_access_errors(self):
        self.write_reports("client/model/run-1/datastructures")
        original_stat = Path.stat

        def stat_with_denied_report(path, *args, **kwargs):
            if path.name == "model.infer.results.json":
                raise PermissionError("report access denied")
            return original_stat(path, *args, **kwargs)

        with (
            patch.object(Path, "stat", stat_with_denied_report),
            self.assertRaisesRegex(PermissionError, "report access denied"),
        ):
            run(self.generation)
        with (
            patch.object(Path, "iterdir", side_effect=PermissionError("directory access denied")),
            self.assertRaisesRegex(PermissionError, "directory access denied"),
        ):
            run(self.generation)

    def test_cli_prints_json_paths_and_keeps_warnings_on_stderr(self):
        group = "client/model/run-1/datastructures"
        reports = self.write_reports(group, ["compilation"])
        with redirect_stdout(io.StringIO()) as output, redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(main([str(self.generation)]), 0)
        self.assertEqual(
            json.loads(output.getvalue()),
            {"client": {"model": {"datastructures": {"run-1": {
                name: str(path) for name, path in reports.items()
            }}}}},
        )
        self.assertIn("missing reports", errors.getvalue())
        self.assertEqual(
            (self.output_dir / self.generation.name / "collection.json").read_text(
                encoding="utf-8",
            ),
            output.getvalue(),
        )

    def test_output_errors_propagate_and_fail_the_cli(self):
        self.write_reports("client/model/run-1/datastructures")
        for operation in ("mkdir", "write_text"):
            with self.subTest(operation=operation), patch.object(
                Path, operation, side_effect=PermissionError("output access denied"),
            ):
                with self.assertRaisesRegex(PermissionError, "output access denied"):
                    run(self.generation)
                with (
                    redirect_stdout(io.StringIO()) as output,
                    redirect_stderr(io.StringIO()) as errors,
                ):
                    self.assertEqual(main([str(self.generation)]), 1)
                self.assertEqual(output.getvalue(), "")
                self.assertIn("output access denied", errors.getvalue())

    def test_cli_reports_collection_failure_without_success_output(self):
        for path in (self.generation, self.root / "missing"):
            with (
                self.subTest(path=path),
                redirect_stdout(io.StringIO()) as output,
                redirect_stderr(io.StringIO()) as errors,
            ):
                self.assertEqual(main([str(path)]), 1)
            self.assertEqual(output.getvalue(), "")
            self.assertIn("Visualisation failed", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
