import csv
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from pipeline.clients.lm_studio import LMStudioClient
from pipeline.main import main, run
from pipeline.preprocessor.preprocessor import run as preprocess
from pipeline.preprocessor.thread_safe import annotate_source
from pipeline.runner.runner import run as generate
from pipeline.verifier.java_verifier import run as verify
from pipeline.visualisation.visualisation import run as visualise


class PipelineTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.generation = self.root / "date:test"
        self.client = Mock(spec=["name", "model", "call"])
        self.client.name = "client"
        self.client.model = "model"
        self.client.call.return_value = "public class Counter { private int value; }"
        self.prompts = [{"datastructure": "counter", "prompt": "generate counter"}]
        self.order = Mock()
        self.stages = {}
        targets = {
            "generate": ("runner.run", {"wraps": generate}),
            "annotate": ("preprocessor.run", {"wraps": preprocess}),
            "verify": ("java_verifier.run", {"return_value": True}),
            "build": ("analyze.database_builder.run", {"return_value": True}),
            "codeql": ("analyze.analyzer.run", {"return_value": True}),
            "infer": ("analyze.racerd.run", {"return_value": True}),
            "sokrates": ("analyze.sokrates_analyzer.run", {"return_value": True}),
        }
        for name, (target, options) in targets.items():
            stage = self.enterContext(patch(f"pipeline.main.{target}", **options))
            self.stages[name] = stage
            self.order.attach_mock(stage, name)
        self.correctness = self.enterContext(patch(
            "pipeline.main.analyze.correctness_analyzer.run", return_value=True,
        ))
        self.order.attach_mock(self.correctness, "correctness")
        self.visualisation = self.enterContext(patch(
            "pipeline.main.visualisation.run", return_value=True,
        ))
        self.order.attach_mock(self.visualisation, "visualisation")
        self.timestamp = self.enterContext(
            patch("pipeline.main.timestamp_now", return_value="date:test")
        )

    def run_pipeline(self, *, generation_dir=None):
        successful = run(
            [self.client], self.prompts, self.root,
            generation_dir=generation_dir,
            codeql="/tools/codeql", infer="/tools/infer", sokrates="/tools/sokrates",
        )
        self.generation = self.stages["generate"].call_args.kwargs["generation_dir"]
        return successful

    def test_generates_annotates_verifies_then_benchmarks_the_same_directory(self):
        self.stages["verify"].side_effect = verify
        self.assertTrue(self.run_pipeline())
        stages = [call[0] for call in self.order.mock_calls]
        self.assertEqual(
            stages,
            ["generate", "annotate", "verify", "build", "codeql", "infer", "sokrates", "correctness", "visualisation"],
        )
        self.correctness.assert_called_once_with(
            self.generation, model="kev:4b-mlx-bf16", api_host="localhost:11434",
        )
        self.timestamp.assert_called_once_with()
        self.stages["generate"].assert_called_once_with(
            [self.client], self.prompts, generation_dir=self.generation
        )
        self.stages["annotate"].assert_called_once_with(self.generation)
        self.stages["verify"].assert_called_once_with(self.generation)
        self.stages["build"].assert_called_once_with(
            self.generation, "/tools/codeql", overwrite=False
        )
        self.stages["codeql"].assert_called_once_with(self.generation, "/tools/codeql")
        self.stages["infer"].assert_called_once_with(self.generation, "/tools/infer")
        self.stages["sokrates"].assert_called_once_with(self.generation, "/tools/sokrates")
        self.visualisation.assert_called_once_with(self.generation)
        source = (
            self.generation / "client" / "model" / "run-1" / "datastructures" / "Counter.java"
        )
        self.assertIn("@Counter.ThreadSafe", source.read_text())
        report = source.parent / "results" / "model.results.json"
        self.assertTrue(json.loads(report.read_text())["files"][0]["compiled"])

    def test_repeated_runs_and_categories_are_annotated_and_compiled_separately(self):
        self.client.runs = 2
        self.stages["verify"].side_effect = verify
        self.prompts.append(
            {
                "datastructure": "NovelCounter", "category": "novel_problems",
                "prompt": "generate novel counter",
            }
        )
        self.assertTrue(self.run_pipeline())
        sources = [
            self.generation / "client" / "model" / run_name / category
            for run_name in ("run-1", "run-2")
            for category in ("datastructures", "novel_problems")
        ]
        for source_dir in sources:
            self.assertIn("@Counter.ThreadSafe", (source_dir / "Counter.java").read_text())
            report = json.loads(
                (source_dir / "results" / "model.results.json").read_text()
            )
            self.assertEqual(report["model"], "model")
            self.assertEqual([record["file"] for record in report["files"]], ["Counter.java"])
            self.assertTrue(report["files"][0]["compiled"])
        for run_name in ("run-1", "run-2"):
            run_results = self.generation / "client" / "model" / run_name / "results"
            self.assertTrue((run_results / "manifest.json").is_file())
            self.assertFalse((run_results / "model.results.json").exists())
        for name in ("build", "codeql", "infer", "sokrates"):
            self.assertEqual(self.stages[name].call_args.args[0], self.generation)

    def test_fenced_response_is_cleaned_and_annotated_before_verification(self):
        self.client.call.return_value = "```java\npublic class Counter {}\n```"
        self.stages["verify"].side_effect = verify
        self.assertTrue(self.run_pipeline())
        source = (
            self.generation / "client" / "model" / "run-1" / "datastructures" / "Counter.java"
        ).read_text()
        self.assertNotIn("```", source)
        self.assertIn("@Counter.ThreadSafe", source)

    def test_evaluation_failures_do_not_block_later_steps(self):
        for stage in ("verify", "build", "codeql", "infer", "sokrates"):
            for outcome in (False, RuntimeError("tool unavailable")):
                with self.subTest(stage=stage, outcome=outcome):
                    for name, mocked in self.stages.items():
                        mocked.reset_mock()
                        if name not in {"generate", "annotate"}:
                            mocked.side_effect = None
                            mocked.return_value = True
                    self.visualisation.reset_mock()
                    if isinstance(outcome, Exception):
                        self.stages[stage].side_effect = outcome
                    else:
                        self.stages[stage].return_value = outcome
                    with patch("sys.stderr") as errors:
                        self.assertFalse(self.run_pipeline())
                    self.assertTrue(errors.write.called)
                    for name in ("verify", "build", "codeql", "infer", "sokrates"):
                        self.stages[name].assert_called_once()
                    self.visualisation.assert_called_once_with(self.generation)

    def test_annotation_failure_stops_before_verification_and_benchmarks(self):
        self.stages["annotate"].side_effect = ValueError("unclosed class")
        with patch("sys.stderr") as errors:
            self.assertFalse(self.run_pipeline())
        self.assertIn("unclosed class", str(errors.write.call_args_list))
        for name in ("verify", "build", "codeql", "infer", "sokrates"):
            self.stages[name].assert_not_called()
        self.visualisation.assert_not_called()

    def test_total_generation_failure_never_evaluates_old_sources(self):
        old_source = self.generation / "client" / "model" / "Old.java"
        old_source.parent.mkdir(parents=True)
        old_source.write_text("class Old {}")
        self.client.call.side_effect = RuntimeError("LLM unavailable")
        self.assertFalse(self.run_pipeline())
        for name in ("annotate", "verify", "build", "codeql", "infer", "sokrates"):
            self.stages[name].assert_not_called()
        self.visualisation.assert_not_called()
        self.assertEqual(old_source.read_text(), "class Old {}")

    @patch.object(LMStudioClient, "call", autospec=True)
    def test_all_skipped_prompts_stop_before_preprocessing_and_evaluation(self, call):
        self.client = LMStudioClient("model", skip_chats=1)
        with patch("sys.stderr") as errors:
            self.assertFalse(self.run_pipeline())
        self.assertIn("no Java files were produced", str(errors.write.call_args_list))
        call.assert_not_called()
        for name in ("annotate", "verify", "build", "codeql", "infer", "sokrates"):
            self.stages[name].assert_not_called()

    @patch.object(LMStudioClient, "call", autospec=True)
    def test_skips_and_failed_remaining_requests_never_evaluate_empty_run(self, call):
        self.client = LMStudioClient("model", skip_chats=1)
        self.prompts.append({"datastructure": "map", "prompt": "generate map"})
        call.side_effect = RuntimeError("generation failed")
        with patch("sys.stderr") as errors:
            self.assertFalse(self.run_pipeline())
        self.assertIn("no Java files were produced", str(errors.write.call_args_list))
        call.assert_called_once()
        for name in ("annotate", "verify", "build", "codeql", "infer", "sokrates"):
            self.stages[name].assert_not_called()

    @patch.object(LMStudioClient, "call", autospec=True)
    def test_skips_are_not_failures_when_remaining_generation_succeeds(self, call):
        self.client = LMStudioClient("model", skip_chats=1)
        self.prompts.append({"datastructure": "map", "prompt": "generate map"})
        call.return_value = "public class Map {}"
        self.stages["verify"].side_effect = verify
        self.assertTrue(self.run_pipeline())
        call.assert_called_once_with(self.client, "generate map")
        for name in ("annotate", "verify", "build", "codeql", "infer", "sokrates"):
            self.stages[name].assert_called_once()
        self.assertEqual([p.name for p in self.generation.rglob("*.java")], ["Map.java"])

    def test_repeated_runs_in_the_same_minute_use_separate_directories(self):
        self.assertTrue(self.run_pipeline())
        first = self.generation
        self.assertTrue(self.run_pipeline())
        self.assertNotEqual(first, self.generation)
        self.assertTrue(first.is_dir())
        self.assertTrue(self.generation.is_dir())

    @patch.object(LMStudioClient, "call", autospec=True)
    def test_resumes_generation_and_evaluates_old_and_new_programs(self, call):
        self.client = LMStudioClient("model", skip_chats=1)
        self.prompts.append({"datastructure": "map", "prompt": "generate map"})
        source_dir = self.generation / "lm-studio" / "model" / "run-1" / "datastructures"
        source_dir.mkdir(parents=True)
        old_source = source_dir / "Counter.java"
        old_source.write_text(annotate_source("public class Counter {}\n"))
        contents = old_source.read_bytes()
        call.return_value = "public class Map {}"
        self.stages["verify"].side_effect = verify

        self.assertTrue(self.run_pipeline(generation_dir=self.generation))

        self.timestamp.assert_not_called()
        call.assert_called_once_with(self.client, "generate map")
        self.assertEqual(old_source.read_bytes(), contents)
        self.assertIn("@Map.ThreadSafe", (source_dir / "Map.java").read_text())
        report = json.loads((source_dir / "results/model.results.json").read_text())
        self.assertEqual({record["file"] for record in report["files"]}, {"Counter.java", "Map.java"})
        self.assertTrue(all(record["compiled"] for record in report["files"]))
        self.stages["build"].assert_called_once_with(
            self.generation, "/tools/codeql", overwrite=True
        )
        for name in ("annotate", "verify", "codeql", "infer"):
            self.assertEqual(self.stages[name].call_args.args[0], self.generation)
        self.assertEqual(list(self.root.iterdir()), [self.generation])

    @patch.object(LMStudioClient, "call", autospec=True)
    def test_resume_can_evaluate_existing_programs_when_all_chats_are_skipped(self, call):
        self.client = LMStudioClient("model", skip_chats=1)
        source_dir = self.generation / "lm-studio" / "model" / "datastructures"
        source_dir.mkdir(parents=True)
        (source_dir / "Counter.java").write_text("public class Counter {}")
        self.assertTrue(self.run_pipeline(generation_dir=self.generation))
        call.assert_not_called()
        for name in ("annotate", "verify", "build", "codeql", "infer", "sokrates"):
            self.stages[name].assert_called_once()

    def test_explicit_new_generation_folder_is_created_without_using_output_root(self):
        destination = self.root / "selected" / "generation"
        unused_output = self.root / "unused"
        self.assertTrue(run(
            [self.client], self.prompts, unused_output, generation_dir=destination
        ))
        self.assertTrue(
            (destination / "client/model/run-1/datastructures/Counter.java").is_file()
        )
        self.assertFalse(unused_output.exists())
        self.timestamp.assert_not_called()

    def test_generation_folder_cannot_be_an_existing_file(self):
        destination = self.root / "file"
        destination.write_text("unchanged")
        with self.assertRaises(FileExistsError):
            run([self.client], self.prompts, generation_dir=destination)
        self.assertEqual(destination.read_text(), "unchanged")
        self.stages["generate"].assert_not_called()

    def test_partial_generation_failure_evaluates_successful_files_but_fails_run(self):
        self.prompts.append({"datastructure": "map", "prompt": "generate map"})
        self.client.call.side_effect = [
            RuntimeError("one failed"), "public class Counter {}",
        ]
        self.assertFalse(self.run_pipeline())
        for name in ("annotate", "verify", "build", "codeql", "infer"):
            self.stages[name].assert_called_once()

    def test_empty_clients_or_prompts_are_rejected(self):
        for clients, prompts in (([], self.prompts), ([self.client], [])):
            with self.subTest(clients=clients, prompts=prompts):
                with self.assertRaisesRegex(ValueError, "at least one"):
                    run(clients, prompts, self.root)
        self.stages["generate"].assert_not_called()

    def test_correctness_evaluates_the_same_generation_after_tool_failures(self):
        self.stages["infer"].return_value = False
        self.assertFalse(run(
            [self.client], self.prompts, self.root,
            generation_dir=self.generation, correctness=True,
            ollama_model="local-decision", ollama_host="localhost:12345",
        ))
        self.correctness.assert_called_once_with(
            self.generation, model="local-decision", api_host="localhost:12345",
        )
        self.assertEqual(
            [call[0] for call in self.order.mock_calls],
            ["generate", "annotate", "verify", "build", "codeql", "infer", "sokrates", "correctness", "visualisation"],
        )

    def test_correctness_failure_marks_full_pipeline_unsuccessful(self):
        self.correctness.return_value = False
        self.assertFalse(run(
            [self.client], self.prompts, self.root,
        ))
        self.correctness.assert_called_once()
        self.visualisation.assert_called_once()

    def test_correctness_can_be_disabled(self):
        self.assertTrue(run(
            [self.client], self.prompts, self.root, correctness=False,
        ))
        self.stages["infer"].assert_called_once()
        self.correctness.assert_not_called()
        self.visualisation.assert_called_once()

    def test_collection_errors_mark_pipeline_unsuccessful(self):
        for error in (
            FileNotFoundError("no reports"), PermissionError("access denied"),
            ValueError("missing manifest mapping"),
        ):
            with self.subTest(error=error):
                self.visualisation.reset_mock()
                self.visualisation.side_effect = error
                with patch("sys.stderr") as errors:
                    self.assertFalse(self.run_pipeline())
                self.visualisation.assert_called_once_with(self.generation)
                self.assertIn("Visualisation failed", str(errors.write.call_args_list))

    def test_real_collection_accepts_partial_compilation_reports(self):
        self.stages["verify"].side_effect = verify
        self.visualisation.side_effect = visualise
        output_dir = self.root / "visualisation"
        with (
            patch("sys.stderr") as errors,
            patch("pipeline.visualisation.collection.DEFAULT_OUTPUT_DIR", output_dir),
        ):
            self.assertTrue(self.run_pipeline())
        self.visualisation.assert_called_once_with(self.generation)
        self.assertIn("missing reports: codeql, infer, correctness, sokrates", str(errors.write.call_args_list))
        report = output_dir / self.generation.name / "collection.json"
        source = self.generation / "client/model/run-1/datastructures"
        self.assertEqual(
            json.loads(report.read_text(encoding="utf-8")),
            {"client": {"model": {"datastructures": {"run-1": {
                "compilation": str(source / "results/model.results.json"),
            }}}}},
        )
        with report.with_name("errors.csv").open(newline="", encoding="utf-8") as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["problem"], "counter")
        self.assertEqual(rows[0]["run"], "1")
        self.assertEqual(rows[0]["compilation_errors"], "0")
        self.assertEqual(rows[0]["codeql_issues"], "")
        self.assertEqual(rows[0]["codeql_status"], "missing")


class PipelineCLITests(unittest.TestCase):
    def test_cli_prompt_selection_and_tool_arguments(self):
        for arguments in ([], ["--skip-prompts"], ["--prompt-file", "input.json"]):
            with self.subTest(arguments=arguments):
                with (
                    patch("pipeline.main.generator.write_prompts", return_value=Path("new.json")) as write,
                    patch("pipeline.main.runner.default_clients", return_value=[]) as clients,
                    patch("pipeline.main.runner.load_prompts", return_value=[]) as load,
                    patch("pipeline.main.run", return_value=True) as pipeline,
                ):
                    self.assertEqual(
                        main(arguments + [
                            "--output-dir", "custom",
                            "--codeql", "/bin/codeql", "--infer", "/bin/infer",
                            "--sokrates", "/bin/sokrates",
                        ]),
                        0,
                    )
                    if not arguments:
                        write.assert_called_once_with()
                        load.assert_called_once_with(Path("new.json"))
                    else:
                        write.assert_not_called()
                        load.assert_called_once_with(
                            None if arguments == ["--skip-prompts"] else Path("input.json")
                        )
                    clients.assert_called_once_with()
                    pipeline.assert_called_once_with(
                        [], [], Path("custom"), generation_dir=None,
                        codeql="/bin/codeql", infer="/bin/infer",
                        sokrates="/bin/sokrates",
                        correctness=True, ollama_model="kev:4b-mlx-bf16",
                        ollama_host="localhost:11434",
                    )

    def test_cli_passes_selected_generation_folder_with_prompt_reuse(self):
        with (
            patch("pipeline.main.generator.write_prompts") as write,
            patch("pipeline.main.runner.default_clients", return_value=[]),
            patch("pipeline.main.runner.load_prompts", return_value=[]) as load,
            patch("pipeline.main.run", return_value=True) as pipeline,
        ):
            self.assertEqual(main([
                "--skip-prompts", "--generation-dir", "Generations/date:chosen",
            ]), 0)
            write.assert_not_called()
            load.assert_called_once_with(None)
            pipeline.assert_called_once_with(
                [], [], Path("Generations"),
                generation_dir=Path("Generations/date:chosen"),
                codeql="codeql", infer="infer",
                sokrates="sokrates",
                correctness=True, ollama_model="kev:4b-mlx-bf16",
                ollama_host="localhost:11434",
            )

    def test_cli_passes_explicit_correctness_and_local_model_settings(self):
        with (
            patch("pipeline.main.runner.default_clients", return_value=[]),
            patch("pipeline.main.runner.load_prompts", return_value=[]),
            patch("pipeline.main.run", return_value=True) as pipeline,
        ):
            self.assertEqual(main([
                "--skip-prompts", "--correctness", "--ollama-model", "local-decision",
                "--ollama-host", "127.0.0.1:12345",
            ]), 0)
            pipeline.assert_called_once_with(
                [], [], Path("Generations"), generation_dir=None,
                codeql="codeql", infer="infer", correctness=True,
                sokrates="sokrates",
                ollama_model="local-decision", ollama_host="127.0.0.1:12345",
            )

    def test_cli_can_disable_correctness(self):
        with (
            patch("pipeline.main.runner.default_clients", return_value=[]),
            patch("pipeline.main.runner.load_prompts", return_value=[]),
            patch("pipeline.main.run", return_value=True) as pipeline,
        ):
            self.assertEqual(main(["--skip-prompts", "--no-correctness"]), 0)
            self.assertFalse(pipeline.call_args.kwargs["correctness"])

    def test_cli_rejects_conflicting_generation_destinations(self):
        with patch("sys.stderr"), self.assertRaises(SystemExit) as error:
            main(["--output-dir", "root", "--generation-dir", "chosen"])
        self.assertEqual(error.exception.code, 2)

    def test_cli_returns_nonzero_for_failed_pipeline_or_missing_input(self):
        with (
            patch("pipeline.main.runner.load_prompts", return_value=[]),
            patch("pipeline.main.run", return_value=False),
        ):
            self.assertEqual(main(["--skip-prompts"]), 1)
        with (
            patch("pipeline.main.runner.load_prompts", side_effect=FileNotFoundError("missing")),
            patch("pipeline.main.run") as pipeline,
            patch("sys.stderr") as errors,
        ):
            self.assertEqual(main(["--prompt-file", "missing.json"]), 1)
            pipeline.assert_not_called()
            self.assertIn("missing", str(errors.write.call_args_list))

if __name__ == "__main__":
    unittest.main()
