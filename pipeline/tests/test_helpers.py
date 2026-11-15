import tempfile
import unittest
from contextlib import chdir
from importlib import import_module
from pathlib import Path
from unittest.mock import patch

from pipeline.helpers import (
    discover_model_directories,
    find_latest_file,
    generation_model_name,
    timestamp_now,
)


class HelperTests(unittest.TestCase):
    generation_steps = (
        "pipeline.preprocessor.preprocessor",
        "pipeline.preprocessor.thread_safe",
        "pipeline.verifier.java_verifier",
        "pipeline.analysis.analyze",
        "pipeline.analysis.codeql.database_builder",
        "pipeline.analysis.codeql.analyzer",
        "pipeline.analysis.infer.racerd",
        "pipeline.analysis.correctness.analyzer",
        "pipeline.visualisation.collection",
        "pipeline.visualisation.visualisation",
    )

    def test_steps_default_to_latest_generation_by_name(self):
        with tempfile.TemporaryDirectory() as directory, chdir(directory):
            root = Path("Generations")
            newer = root / "date:2026-10-22T11-00-abc"
            older = root / "date:2026-10-21T11-00"
            newer.mkdir(parents=True)
            older.mkdir()
            older.touch()
            (root / "notes").mkdir()
            (root / "date:2099-01-01T00-00").write_text("not a directory")
            for name in self.generation_steps:
                module = import_module(name)
                result = 0 if ".preprocessor." in name else True
                if name == "pipeline.visualisation.collection":
                    result = {"client": {"model": {"compilation": Path("report.results.json")}}}
                with self.subTest(step=name), patch.object(module, "run", return_value=result) as run:
                    self.assertEqual(module.main([]), 0)
                    self.assertEqual(run.call_args.args[0].resolve(), newer.resolve())

    def test_explicit_step_input_overrides_default_without_generations(self):
        with tempfile.TemporaryDirectory() as directory, chdir(directory):
            chosen = Path("chosen")
            chosen.mkdir()
            for name in self.generation_steps:
                module = import_module(name)
                args = ["--input-dir", str(chosen)] if ".verifier." in name else [str(chosen)]
                result = 0 if ".preprocessor." in name else True
                if name == "pipeline.visualisation.collection":
                    result = {"client": {"model": {"compilation": Path("report.results.json")}}}
                with self.subTest(step=name), patch.object(module, "run", return_value=result) as run:
                    self.assertEqual(module.main(args), 0)
                    self.assertEqual(run.call_args.args[0].resolve(), chosen.resolve())

    def test_steps_report_missing_generations_instead_of_running(self):
        with tempfile.TemporaryDirectory() as directory, chdir(directory):
            for has_root in (False, True):
                if has_root:
                    Path("Generations").mkdir()
                for name in self.generation_steps:
                    module = import_module(name)
                    with (
                        self.subTest(step=name, has_root=has_root),
                        patch.object(module, "run") as run,
                        patch("sys.stderr") as errors,
                        self.assertRaises(SystemExit) as error,
                    ):
                        module.main([])
                    self.assertEqual(error.exception.code, 2)
                    self.assertIn("No generation directories found", str(errors.write.call_args_list))
                    run.assert_not_called()

    def test_step_help_works_without_any_generation(self):
        with tempfile.TemporaryDirectory() as directory, chdir(directory):
            for name in self.generation_steps:
                module = import_module(name)
                with (
                    self.subTest(step=name),
                    patch.object(module, "run") as run,
                    patch("sys.stdout"),
                    self.assertRaises(SystemExit) as error,
                ):
                    module.main(["--help"])
                self.assertEqual(error.exception.code, 0)
                run.assert_not_called()

    def test_timestamp_uses_date_hour_and_minute(self):
        self.assertRegex(
            timestamp_now(),
            r"^date:\d{4}-\d{2}-\d{2}T\d{2}-\d{2}$",
        )

    def test_finds_latest_matching_file_and_ignores_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            older = root / "date:older.json"
            newer = root / "date:newer.json"
            ignored = root / "date:ignored.txt"
            older.write_text("older", encoding="utf-8")
            newer.write_text("newer", encoding="utf-8")
            ignored.write_text("ignored", encoding="utf-8")
            (root / "date:directory.json").mkdir()
            older.touch()
            newer.touch()

            self.assertEqual(find_latest_file(root, "date:*.json"), newer)

    def test_raises_when_no_matching_files_exist(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                find_latest_file(Path(directory), "date:*.json")

    def test_discovers_sorted_model_directories_and_skips_legacy_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "copilot" / "b-model").mkdir(parents=True)
            (root / "copilot" / "a-model").mkdir()
            (root / "results" / "copilot").mkdir(parents=True)
            (root / "notes.txt").write_text("", encoding="utf-8")

            self.assertEqual(
                discover_model_directories(root),
                [root / "copilot" / "a-model", root / "copilot" / "b-model"],
            )

    def test_discover_rejects_missing_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(NotADirectoryError):
                discover_model_directories(Path(directory) / "missing")

    def test_discovers_numbered_runs_and_legacy_sources_without_nested_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "client" / "model"
            sources = [model / "datastructures"]
            sources.extend(
                model / run_name / category
                for run_name in ("run-1", "run-2")
                for category in ("datastructures", "novel_problems", "advanced_datastructures")
            )
            for source in sources:
                source.mkdir(parents=True)
                (source / "Example.java").write_text("class Example {}")
                (source / "results" / "run-99" / "datastructures").mkdir(parents=True)
                self.assertEqual(generation_model_name(source), "model")
            (model / "Legacy.java").write_text("class Legacy {}")
            (model / "results" / "run-99" / "datastructures").mkdir(parents=True)
            for invalid in ("run-0", "run-01", "run--1", "run-one", "run-1-extra"):
                (model / invalid / "datastructures").mkdir(parents=True)
            self.assertEqual(
                discover_model_directories(root),
                sorted([model, *sources], key=lambda path: path.relative_to(root).as_posix()),
            )
            (model / "Legacy.java").unlink()
            self.assertEqual(
                discover_model_directories(root),
                sorted(sources, key=lambda path: path.relative_to(root).as_posix()),
            )

    def test_run_only_models_do_not_get_extra_empty_model_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "client" / "model" / "run-1" / "datastructures"
            source.mkdir(parents=True)
            self.assertEqual(discover_model_directories(root), [source])
            self.assertEqual(generation_model_name(source), "model")

    def test_discovers_categories_and_legacy_sources_without_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "client" / "model"
            for category in ("datastructures", "novel_problems"):
                source_dir = model / category
                source_dir.mkdir(parents=True)
                (source_dir / "Example.java").write_text("class Example {}")
                (source_dir / "results" / "database").mkdir(parents=True)
            (model / "results").mkdir()
            (model / "Legacy.java").write_text("class Legacy {}")
            (root / "other" / "legacy").mkdir(parents=True)
            self.assertEqual(
                discover_model_directories(root),
                [
                    model, model / "datastructures", model / "novel_problems",
                    root / "other" / "legacy",
                ],
            )
            (model / "Legacy.java").unlink()
            self.assertEqual(
                discover_model_directories(root),
                [
                    model / "datastructures", model / "novel_problems",
                    root / "other" / "legacy",
                ],
            )
            for category in ("datastructures", "novel_problems"):
                self.assertEqual(generation_model_name(model / category), "model")
            self.assertEqual(generation_model_name(model), "model")


if __name__ == "__main__":
    unittest.main()
