import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from pipeline.analysis.analyze import main, run


class AnalysisTests(unittest.TestCase):
    def setUp(self):
        self.root = Path("Generations/date:test")
        self.order = Mock()
        self.stages = {}
        for name, target in (
            ("build", "database_builder.run"),
            ("codeql", "analyzer.run"),
            ("infer", "racerd.run"),
            ("sokrates", "sokrates_analyzer.run"),
        ):
            stage = self.enterContext(
                patch(f"pipeline.analysis.analyze.{target}", return_value=True)
            )
            self.stages[name] = stage
            self.order.attach_mock(stage, name)
        self.correctness = self.enterContext(patch(
            "pipeline.analysis.analyze.correctness_analyzer.run", return_value=True,
        ))
        self.order.attach_mock(self.correctness, "correctness")

    def test_builds_then_runs_all_analyzers_on_the_same_generation(self):
        self.assertTrue(run(
            self.root, codeql="/tools/codeql", infer="/tools/infer", overwrite=True
        ))
        self.assertEqual(
            [call[0] for call in self.order.mock_calls],
            ["build", "codeql", "infer", "sokrates", "correctness"],
        )
        self.stages["build"].assert_called_once_with(
            self.root, "/tools/codeql", overwrite=True
        )
        self.stages["codeql"].assert_called_once_with(self.root, "/tools/codeql")
        self.stages["infer"].assert_called_once_with(self.root, "/tools/infer")
        self.stages["sokrates"].assert_called_once_with(self.root, "sokrates")
        self.correctness.assert_called_once_with(
            self.root, model="kev:4b-mlx-bf16", api_host="localhost:11434",
        )

    def test_runs_correctness_after_existing_tools_with_selected_configuration(self):
        self.assertTrue(run(
            self.root, correctness=True, ollama_model="local-decision",
            ollama_host="localhost:12345",
        ))
        self.assertEqual(
            [call[0] for call in self.order.mock_calls],
            ["build", "codeql", "infer", "sokrates", "correctness"],
        )
        self.correctness.assert_called_once_with(
            self.root, model="local-decision", api_host="localhost:12345",
        )

    def test_correctness_runs_after_tool_failures_and_propagates_its_own_failures(self):
        self.stages["infer"].return_value = False
        self.assertFalse(run(self.root, correctness=True))
        self.correctness.assert_called_once()
        self.stages["infer"].return_value = True
        for outcome in (False, RuntimeError("server unavailable"), ValueError("invalid score")):
            with self.subTest(outcome=outcome):
                self.correctness.reset_mock(side_effect=True)
                if isinstance(outcome, Exception):
                    self.correctness.side_effect = outcome
                else:
                    self.correctness.return_value = outcome
                self.assertFalse(run(self.root, correctness=True))
                self.correctness.assert_called_once()

    def test_defaults_do_not_overwrite_existing_databases(self):
        self.assertTrue(run(self.root))
        self.stages["build"].assert_called_once_with(
            self.root, "codeql", overwrite=False
        )
        self.stages["codeql"].assert_called_once_with(self.root, "codeql")
        self.stages["infer"].assert_called_once_with(self.root, "infer")
        self.stages["sokrates"].assert_called_once_with(self.root, "sokrates")

    def test_correctness_can_be_disabled(self):
        self.assertTrue(run(self.root, correctness=False))
        self.assertEqual(
            [call[0] for call in self.order.mock_calls],
            ["build", "codeql", "infer", "sokrates"],
        )
        self.correctness.assert_not_called()

    def test_failed_stages_are_reported_without_blocking_remaining_tools(self):
        for stage in self.stages:
            for outcome in (
                False, OSError("tool unavailable"), RuntimeError("tool failed"),
                ValueError("invalid report"),
            ):
                with self.subTest(stage=stage, outcome=outcome):
                    for mocked in self.stages.values():
                        mocked.reset_mock()
                        mocked.side_effect = None
                        mocked.return_value = True
                    if isinstance(outcome, Exception):
                        self.stages[stage].side_effect = outcome
                    else:
                        self.stages[stage].return_value = outcome
                    with patch("sys.stderr") as errors:
                        self.assertFalse(run(self.root))
                    self.assertIn("Analysis step failed", str(errors.write.call_args_list))
                    for mocked in self.stages.values():
                        mocked.assert_called_once()


class AnalysisCLITests(unittest.TestCase):
    @patch("pipeline.analysis.analyze.run", return_value=True)
    def test_cli_passes_directory_tools_and_overwrite(self, analyze):
        self.assertEqual(main([
            "Generations/date:test", "--codeql", "/tools/codeql",
            "--infer", "/tools/infer", "--sokrates",
            "java -jar /tools/sokrates.jar", "--overwrite",
        ]), 0)
        analyze.assert_called_once_with(
            Path("Generations/date:test").resolve(),
            codeql="/tools/codeql", infer="/tools/infer", overwrite=True,
            sokrates="java -jar /tools/sokrates.jar",
            correctness=True, ollama_model="kev:4b-mlx-bf16",
            ollama_host="localhost:11434",
        )

    @patch("pipeline.analysis.analyze.run", return_value=False)
    def test_cli_defaults_and_failure_exit_status(self, analyze):
        self.assertEqual(main(["Generations/date:test"]), 1)
        analyze.assert_called_once_with(
            Path("Generations/date:test").resolve(),
            codeql="codeql", infer="infer", overwrite=False,
            sokrates="sokrates",
            correctness=True, ollama_model="kev:4b-mlx-bf16",
            ollama_host="localhost:11434",
        )

    @patch("pipeline.analysis.analyze.run", return_value=True)
    def test_cli_passes_explicit_correctness_and_ollama_configuration(self, analyze):
        self.assertEqual(main([
            "Generations/date:test", "--correctness", "--ollama-model", "local-decision",
            "--ollama-host", "127.0.0.1:12345",
        ]), 0)
        analyze.assert_called_once_with(
            Path("Generations/date:test").resolve(),
            codeql="codeql", infer="infer", overwrite=False, correctness=True,
            sokrates="sokrates",
            ollama_model="local-decision", ollama_host="127.0.0.1:12345",
        )

    @patch("pipeline.analysis.analyze.run", return_value=True)
    def test_cli_can_disable_correctness(self, analyze):
        self.assertEqual(main(["Generations/date:test", "--no-correctness"]), 0)
        self.assertFalse(analyze.call_args.kwargs["correctness"])

if __name__ == "__main__":
    unittest.main()
