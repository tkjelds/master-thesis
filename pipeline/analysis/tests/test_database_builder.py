import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.analysis.codeql.database_builder import (
    BUILD_SCRIPT,
    create_command,
    database_path,
    run,
)


class DatabaseBuilderTests(unittest.TestCase):
    def test_database_is_placed_in_model_results_directory(self):
        model_dir = Path("date:x") / "copilot" / "gpt-5.6-luna"

        self.assertEqual(
            database_path(model_dir),
            model_dir / "results" / "gpt-5.6-luna.codeql-db",
        )

    def test_command_traces_build_script_from_model_directory(self):
        model_dir = Path("/data/copilot/gpt-5.6-luna")

        command = create_command(model_dir, overwrite=True)

        self.assertEqual(command[:3], ["codeql", "database", "create"])
        self.assertEqual(command[3], str(database_path(model_dir)))
        self.assertIn("--language=java-kotlin", command)
        self.assertIn("--build-mode=manual", command)
        self.assertIn(f"--command=sh {BUILD_SCRIPT}", command)
        self.assertIn(f"--source-root={model_dir}", command)
        self.assertEqual(command[-1], "--overwrite")
        self.assertTrue(BUILD_SCRIPT.is_file())

    @patch("pipeline.analysis.codeql.database_builder.subprocess.run")
    def test_builds_separate_run_and_category_databases_with_original_model_name(self, mocked_run):
        mocked_run.return_value.returncode = 0
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sources = [
                root / "client" / "model" / run_name / category
                for run_name in ("run-1", "run-2")
                for category in ("datastructures", "novel_problems")
            ]
            for source_dir in sources:
                source_dir.mkdir(parents=True)
                (source_dir / "Example.java").write_text("class Example {}")
            self.assertTrue(run(root))
            self.assertEqual(mocked_run.call_count, 4)
            for call, source_dir in zip(mocked_run.call_args_list, sources):
                command = call.args[0]
                self.assertEqual(
                    command[3], str(source_dir / "results" / "model.codeql-db")
                )
                self.assertIn(f"--source-root={source_dir}", command)

    @patch("pipeline.analysis.codeql.database_builder.subprocess.run")
    def test_runs_each_model_with_java_and_skips_empty_ones(self, mocked_run):
        mocked_run.return_value.returncode = 0
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = root / "copilot" / "luna"
            model_dir.mkdir(parents=True)
            (model_dir / "A.java").write_text("class A {}\n", encoding="utf-8")
            (root / "copilot" / "empty").mkdir()

            self.assertTrue(run(root))

            self.assertEqual(mocked_run.call_count, 1)
            command = mocked_run.call_args.args[0]
            self.assertEqual(command[3], str(database_path(model_dir)))
            self.assertFalse(mocked_run.call_args.kwargs["shell"])
            self.assertTrue((model_dir / "results").is_dir())

    @patch("pipeline.analysis.codeql.database_builder.subprocess.run")
    def test_failure_is_reported_and_partial_database_removed(self, mocked_run):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = root / "copilot" / "luna"
            model_dir.mkdir(parents=True)
            (model_dir / "A.java").write_text("class A {}\n", encoding="utf-8")

            def fail(*args, **kwargs):
                database_path(model_dir).mkdir(parents=True)
                return type("Completed", (), {"returncode": 2})()

            mocked_run.side_effect = fail

            self.assertFalse(run(root))
            self.assertFalse(database_path(model_dir).exists())

    @patch("pipeline.analysis.codeql.database_builder.subprocess.run")
    def test_existing_database_is_kept_when_not_overwriting(self, mocked_run):
        mocked_run.return_value.returncode = 2
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = root / "copilot" / "luna"
            database_path(model_dir).mkdir(parents=True)
            (model_dir / "A.java").write_text("class A {}\n", encoding="utf-8")

            self.assertFalse(run(root))
            self.assertTrue(database_path(model_dir).exists())


if __name__ == "__main__":
    unittest.main()
