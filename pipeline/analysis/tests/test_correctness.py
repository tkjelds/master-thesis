import json
import subprocess
import sys
import tempfile
import threading
import unittest
from contextlib import chdir
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch

from pipeline.analysis.correctness.analyzer import INSTRUCTIONS, main, report_path, run
from pipeline.clients.lm_studio import LMStudioClient
from pipeline.runner.runner import MANIFEST_FILENAME, load_prompts, run as generate


class CorrectnessTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.generation = self.root / "generation"
        self.generation.mkdir()
        self.enterContext(patch(
            "pipeline.analysis.correctness.analyzer.REPOSITORY_ROOT", self.root,
        ))
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.side_effect = ["public class SafeTable {}", "public class Cache {}"]
        prompts = [
            {
                "name": "datastructures/map", "datastructure": "map",
                "category": "datastructures", "prompt": "Original map description",
            },
            {
                "name": "novel_problems/cache", "datastructure": "cache",
                "category": "novel_problems", "prompt": "Original cache description",
            },
        ]
        self.assertEqual(generate([client], prompts, generation_dir=self.generation), [])
        self.manifest_path = self.generation / MANIFEST_FILENAME
        self.entries = json.loads(self.manifest_path.read_text())["generations"]
        self.sources = [self.generation / entry["java_file"] for entry in self.entries]
        self.client_type = self.enterContext(
            patch("pipeline.analysis.correctness.analyzer.System1Client")
        )
        self.judge = self.client_type.return_value
        self.judge.model_digest.return_value = "digest123"
        self.judge.probability.return_value = 0.8
        self.enterContext(patch("builtins.print"))

    def save_manifest(self, entries=None):
        self.manifest_path.write_text(json.dumps({
            "generations": self.entries if entries is None else entries,
        }))

    def records(self):
        return [
            record
            for path in sorted(self.generation.rglob("*.correctness.results.json"))
            for record in json.loads(path.read_text())["files"]
        ]

    def test_runner_manifest_maps_exact_saved_prompts_across_categories(self):
        original = {
            path: path.read_bytes()
            for path in self.generation.rglob("*") if path.is_file()
        }
        compiler_report = self.sources[0].parent / "results/model.results.json"
        compiler_report.parent.mkdir()
        compiler_report.write_text('{"compiled": true}')
        with patch.dict("pipeline.prompt_generator.problems.PROBLEM_CATEGORIES", {
            "datastructures": [], "novel_problems": [],
        }):
            self.assertTrue(run(
                self.generation, model="custom-decision", api_host="localhost:12345",
            ))
        self.client_type.assert_called_once_with(
            model="custom-decision", api_host="localhost:12345",
        )
        self.judge.model_digest.assert_called_once_with()
        for entry, source, call in zip(
            self.entries, self.sources, self.judge.probability.call_args_list,
        ):
            metadata = next(
                record for record in load_prompts(self.generation / entry["prompt_file"])
                if record["name"] == entry["prompt_name"]
            )
            self.assertEqual(call.args[0], {
                "specification": metadata["prompt"], "java_source": source.read_text(),
            })
            self.assertEqual(call.kwargs["instructions"], INSTRUCTIONS)
            report = json.loads(report_path(source.parent).read_text())
            self.assertEqual(report["model"], "model")
            self.assertEqual(report["evaluator_model"], "custom-decision")
            self.assertEqual(report["evaluator_digest"], "digest123")
            self.assertEqual(report["rubric_version"], "functional-v1")
            self.assertEqual(report["files"], [{
                "file": source.relative_to(self.root).as_posix(),
                "prompt_name": entry["prompt_name"], "problem": entry["problem"],
                "analyzed": True, "probability": 0.8, "error": "",
            }])
        self.assertEqual(compiler_report.read_text(), '{"compiled": true}')
        for path, contents in original.items():
            self.assertEqual(path.read_bytes(), contents)

    def test_result_file_paths_are_relative_to_repository_not_working_directory(self):
        with chdir(self.generation):
            self.assertTrue(run(self.generation))
        self.assertEqual(
            {record["file"] for record in self.records()},
            {source.relative_to(self.root).as_posix() for source in self.sources},
        )
        self.assertTrue(all("java_file" not in record for record in self.records()))

    def test_follows_manifest_corpus_link_and_prompt_name_rather_than_guessing_sidecar(self):
        old_path = self.generation / self.entries[0]["prompt_file"]
        linked = self.generation / "prompts" / "saved-prompts.json"
        linked.write_bytes(old_path.read_bytes())
        self.entries[0]["prompt_file"] = "prompts/saved-prompts.json"
        self.sources[0].with_suffix(".prompt.json").write_text('{"prompt":"wrong sidecar"}')
        self.save_manifest()
        self.assertTrue(run(self.generation))
        self.assertEqual(
            self.judge.probability.call_args_list[0].args[0]["specification"],
            "Original map description",
        )

    def test_corpus_can_be_linked_from_the_repository_prompts_directory(self):
        corpus_dir = self.root / "prompts"
        corpus_dir.mkdir()
        corpus = corpus_dir / "original.json"
        corpus.write_bytes((self.generation / self.entries[0]["prompt_file"]).read_bytes())
        for entry in self.entries:
            entry["prompt_file"] = "../prompts/original.json"
        self.save_manifest()
        with patch("pipeline.analysis.correctness.analyzer.DEFAULT_PROMPT_DIR", corpus_dir):
            self.assertTrue(run(self.generation))
        self.assertEqual(self.judge.probability.call_count, 2)

    def test_legacy_sidecar_manifest_remains_readable_and_checks_identity(self):
        entry = self.entries[0]
        metadata = {
            "name": entry["prompt_name"], "datastructure": entry["problem"],
            "category": entry["category"], "client": entry["client"],
            "model": entry["model"], "java_file": self.sources[0].name,
            "prompt": "Original map description",
        }
        path = self.sources[0].with_suffix(".prompt.json")
        path.write_text(json.dumps(metadata))
        entry.pop("prompt_file")
        entry["metadata_file"] = path.relative_to(self.generation).as_posix()
        self.save_manifest()
        self.assertTrue(run(self.generation))
        metadata["name"] = "wrong"
        path.write_text(json.dumps(metadata))
        self.assertFalse(run(self.generation))
        self.assertIn("disagrees", self.records()[0]["error"])

    def test_same_problem_in_multiple_encoded_model_directories_is_not_a_duplicate(self):
        client = LMStudioClient(model="publisher/model", reasoning="off")
        with patch.object(LMStudioClient, "call", return_value="public class OtherTable {}"):
            self.assertEqual(generate([client], [{
                "name": "datastructures/map", "datastructure": "map",
                "category": "datastructures", "prompt": "Another saved map prompt",
            }], generation_dir=self.generation), [])
        self.assertTrue(run(self.generation))
        self.assertEqual(len(self.records()), 3)
        self.assertEqual(self.judge.probability.call_count, 3)
        output = (
            self.generation / "lm-studio/publisher%2Fmodel@reasoning-off/run-1/datastructures"
            / "results/publisher%2Fmodel@reasoning-off.correctness.results.json"
        )
        self.assertTrue(output.is_file())

    def test_missing_or_malformed_manifest_never_falls_back_to_sidecars(self):
        for contents in (None, "not json", "[]", '{"generations":{}}'):
            with self.subTest(contents=contents):
                if contents is None:
                    self.manifest_path.unlink()
                else:
                    self.manifest_path.write_text(contents)
                self.assertFalse(run(self.generation))
                self.assertEqual(len(self.records()), 2)
                self.assertTrue(all(
                    record["probability"] is None and "manifest" in record["error"]
                    for record in self.records()
                ))
        self.judge.probability.assert_not_called()
        self.judge.model_digest.assert_not_called()

    def test_repeated_model_runs_get_independent_scores_and_reports(self):
        client = LMStudioClient("repeated-model", runs=2)
        with patch.object(LMStudioClient, "call", side_effect=[
            "public class Table { int first; }", "public class Table { int second; }",
        ]):
            self.assertEqual(generate([client], [{
                "datastructure": "map", "prompt": "Repeated map specification",
            }], generation_dir=self.generation), [])
        self.judge.probability.side_effect = [0.8, 0.8, 0.3, 0.9]
        self.assertTrue(run(self.generation))
        self.assertEqual(self.judge.probability.call_count, 4)
        for number, probability in ((1, 0.3), (2, 0.9)):
            source_dir = (
                self.generation / "lm-studio" / "repeated-model"
                / f"run-{number}" / "datastructures"
            )
            report = json.loads(report_path(source_dir).read_text())
            self.assertEqual(report["model"], "repeated-model")
            self.assertEqual(len(report["files"]), 1)
            self.assertEqual(report["files"][0]["probability"], probability)
            call = self.judge.probability.call_args_list[number + 1]
            self.assertEqual(call.args[0]["specification"], "Repeated map specification")
            self.assertEqual(call.args[0]["java_source"], (source_dir / "Table.java").read_text())

    def test_legacy_categorized_manifest_can_be_analyzed_alongside_numbered_runs(self):
        original = self.sources[0]
        legacy = self.generation / "client" / "model" / "datastructures" / original.name
        legacy.parent.mkdir(parents=True)
        original.replace(legacy)
        self.entries[0]["java_file"] = legacy.relative_to(self.generation).as_posix()
        self.entries[0].pop("run")
        self.save_manifest()
        self.assertTrue(run(self.generation))
        self.assertEqual(self.judge.probability.call_count, 2)
        report = json.loads(report_path(legacy.parent).read_text())
        self.assertEqual(report["model"], "model")
        self.assertTrue(report["files"][0]["analyzed"])

    def test_run_metadata_must_match_source_directory(self):
        for run_name in (None, "", "run-2", 1, True, []):
            with self.subTest(run=run_name):
                self.judge.probability.reset_mock()
                if run_name is None:
                    self.entries[0].pop("run", None)
                else:
                    self.entries[0]["run"] = run_name
                self.save_manifest()
                self.assertFalse(run(self.generation))
                self.assertIn("run disagrees", self.records()[0]["error"])
                self.assertTrue(self.records()[1]["analyzed"])
                self.judge.probability.assert_called_once()

    def test_manifest_rejects_invalid_numbered_run_paths(self):
        for run_name in ("run-0", "run-01", "run--1", "run-one", "run-1-extra"):
            with self.subTest(run=run_name):
                self.judge.probability.reset_mock()
                entry = {
                    **self.entries[0], "run": run_name,
                    "java_file": f"client/model/{run_name}/datastructures/Table.java",
                }
                self.save_manifest([*self.entries, entry])
                self.assertFalse(run(self.generation))
                self.assertEqual(self.judge.probability.call_count, 2)
                self.assertEqual(len(self.records()), 2)
                self.assertFalse((self.generation / "client/model" / run_name).exists())

    def test_unmapped_and_duplicate_sources_fail_without_blocking_valid_sources(self):
        unmapped = self.sources[0].with_name("Unmapped.java")
        unmapped.write_text("class Unmapped {}")
        self.save_manifest([*self.entries, self.entries[0]])
        self.assertFalse(run(self.generation))
        records = {Path(record["file"]).name: record for record in self.records()}
        self.assertIn("Duplicate", records["SafeTable.java"]["error"])
        self.assertIn("No manifest mapping", records["Unmapped.java"]["error"])
        self.assertTrue(records["Cache.java"]["analyzed"])
        self.judge.probability.assert_called_once()

    def test_invalid_entries_do_not_block_other_mappings(self):
        self.save_manifest([None, {}, *self.entries])
        self.assertFalse(run(self.generation))
        self.assertTrue(all(record["analyzed"] for record in self.records()))

    def test_missing_source_or_linked_corpus_is_an_explicit_failed_record(self):
        self.sources[0].unlink()
        (self.generation / self.entries[1]["prompt_file"]).unlink()
        self.assertFalse(run(self.generation))
        self.assertEqual(len(self.records()), 2)
        self.assertTrue(all(
            not record["analyzed"] and record["probability"] is None and record["error"]
            for record in self.records()
        ))
        self.assertEqual(
            {record["file"] for record in self.records()},
            {source.relative_to(self.root).as_posix() for source in self.sources},
        )
        self.assertTrue(all("java_file" not in record for record in self.records()))
        self.judge.probability.assert_not_called()

    def test_corpus_identity_must_agree_with_manifest(self):
        path = self.generation / self.entries[0]["prompt_file"]
        original = json.loads(path.read_text())
        for key in ("name", "datastructure", "category", "prompt"):
            with self.subTest(key=key):
                self.judge.probability.reset_mock()
                changed = json.loads(json.dumps(original))
                changed["prompts"][0][key] = ""
                path.write_text(json.dumps(changed))
                self.assertFalse(run(self.generation))
                self.assertIsNone(self.records()[0]["probability"])
                self.assertTrue(self.records()[1]["analyzed"])
                self.judge.probability.assert_called_once()
        for contents in ("not json", "[]"):
            path.write_text(contents)
            self.assertFalse(run(self.generation))

    def test_missing_or_duplicate_problem_names_do_not_select_an_arbitrary_prompt(self):
        path = self.generation / self.entries[0]["prompt_file"]
        records = json.loads(path.read_text())["prompts"]
        for prompts in ([records[1]], [records[0], *records]):
            with self.subTest(count=len(prompts)):
                self.judge.probability.reset_mock()
                path.write_text(json.dumps({"prompts": prompts}))
                self.assertFalse(run(self.generation))
                self.assertIn("exactly one", self.records()[0]["error"])
                self.assertTrue(self.records()[1]["analyzed"])
                self.judge.probability.assert_called_once()

    def test_corpus_paths_cannot_read_unrelated_files_or_escape_through_symlinks(self):
        outside = self.root / "outside.json"
        outside.write_text("must not read")
        symlink = self.generation / "prompts" / "linked.json"
        symlink.symlink_to(outside)
        read_text = Path.read_text
        for unsafe in ("../outside.json", str(outside), "prompts/linked.json"):
            with self.subTest(unsafe=unsafe):
                self.judge.probability.reset_mock()
                self.entries[0]["prompt_file"] = unsafe
                self.save_manifest()
                with patch.object(Path, "read_text", autospec=True) as read:
                    def guarded(path, *args, **kwargs):
                        self.assertNotEqual(path.resolve(), outside)
                        return read_text(path, *args, **kwargs)
                    read.side_effect = guarded
                    self.assertFalse(run(self.generation))
                self.assertIsNone(self.records()[0]["probability"])
                self.assertTrue(self.records()[1]["analyzed"])
                self.judge.probability.assert_called_once()

    def test_invalid_identity_values_still_produce_strict_json_error_records(self):
        self.entries[0]["problem"] = float("nan")
        self.entries[0]["prompt_name"] = ["invalid"]
        self.save_manifest()
        self.assertFalse(run(self.generation))
        record = self.records()[0]
        self.assertIsNone(record["problem"])
        self.assertIsNone(record["prompt_name"])
        self.assertIsNone(record["probability"])
        self.assertIn("nonblank", record["error"])
        self.assertTrue(self.records()[1]["analyzed"])

    def test_unsafe_source_paths_and_report_symlinks_are_rejected(self):
        outside = self.root / "Outside.java"
        outside.write_text("must not read or overwrite")
        self.sources[0].unlink()
        self.sources[0].symlink_to(outside)
        self.save_manifest([
            *self.entries,
            {**self.entries[0], "java_file": "../Outside.java"},
        ])
        self.assertFalse(run(self.generation))
        self.assertIsNone(self.records()[0]["probability"])
        output = report_path(self.sources[1].parent)
        output.unlink()
        output.symlink_to(outside)
        self.assertFalse(run(self.generation))
        self.assertEqual(outside.read_text(), "must not read or overwrite")

    def test_model_preflight_failure_is_reported_without_repeated_requests(self):
        for error in (
            RuntimeError("server unavailable"),
            OSError("connection refused"),
            ValueError("invalid JSON"),
        ):
            with self.subTest(error=error):
                self.judge.model_digest.reset_mock()
                self.judge.model_digest.side_effect = error
                self.assertFalse(run(self.generation))
                self.judge.model_digest.assert_called_once()
                self.assertTrue(all(
                    record["error"] == str(error) and record["probability"] is None
                    for record in self.records()
                ))
        self.judge.probability.assert_not_called()

    def test_request_failures_continue_and_reruns_replace_stale_scores(self):
        self.assertTrue(run(self.generation))
        self.judge.probability.side_effect = [RuntimeError("input exceeds context"), 0.0]
        self.assertFalse(run(self.generation))
        records = self.records()
        self.assertIsNone(records[0]["probability"])
        self.assertIn("context", records[0]["error"])
        self.assertEqual(records[1]["probability"], 0.0)
        self.assertTrue(records[1]["analyzed"])

    def test_empty_generation_cannot_succeed(self):
        empty = self.root / "empty"
        empty.mkdir()
        (empty / MANIFEST_FILENAME).write_text('{"generations":[]}')
        self.assertFalse(run(empty))
        self.judge.probability.assert_not_called()


class CorrectnessCLITests(unittest.TestCase):
    @patch("pipeline.analysis.correctness.analyzer.run", return_value=True)
    def test_standalone_cli_passes_model_host_and_generation(self, analyze):
        self.assertEqual(main([
            "Generations/date:test", "--ollama-model", "local-decision",
            "--ollama-host", "127.0.0.1:12345",
        ]), 0)
        analyze.assert_called_once_with(
            Path("Generations/date:test"), model="local-decision", api_host="127.0.0.1:12345",
        )

    @patch("pipeline.analysis.correctness.analyzer.run")
    def test_cli_returns_nonzero_for_failed_analysis_and_input_errors(self, analyze):
        analyze.return_value = False
        self.assertEqual(main(["missing"]), 1)
        analyze.side_effect = FileNotFoundError("missing")
        with patch("sys.stderr"):
            self.assertEqual(main(["missing"]), 1)


class CorrectnessEndToEndTests(unittest.TestCase):
    def test_standalone_cli_uses_real_http_and_persists_success_and_failure(self):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def respond(self, status, document):
                body = json.dumps(document).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                requests.append((self.path, None))
                self.respond(200, {
                    "models": [{"name": "kev:4b-mlx-bf16", "digest": "fixture-digest"}],
                })

            def do_POST(self):
                document = json.loads(
                    self.rfile.read(int(self.headers["Content-Length"])),
                )
                requests.append((self.path, document))
                if document["state"]["specification"] == "First specification":
                    self.respond(400, {"error": "input exceeds context"})
                else:
                    self.respond(200, {
                        "model": "kev:4b-mlx-bf16",
                        "answers": {"correctness": {"type": "noul", "noul": 0.75}},
                        "usage": {"input_tokens": 100, "output_tokens": 0},
                    })

            def log_message(self, *args):
                pass

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = Mock(spec=["name", "model", "call"])
            client.name = "client"
            client.model = "model"
            client.call.side_effect = ["public class First {}", "public class Second {}"]
            self.assertEqual(generate([client], [
                {"datastructure": "first", "prompt": "First specification"},
                {"datastructure": "second", "prompt": "Second specification"},
            ], generation_dir=root), [])
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                completed = subprocess.run(
                    [
                        sys.executable, "-m", "pipeline.analysis.correctness", str(root),
                        "--ollama-host", f"127.0.0.1:{server.server_port}",
                    ],
                    capture_output=True, text=True, check=False, timeout=30,
                )
            finally:
                server.shutdown()
                thread.join()
                server.server_close()
            self.assertEqual(completed.returncode, 1)
            report = json.loads(
                report_path(root / "client/model/run-1/datastructures").read_text()
            )
            self.assertEqual(report["evaluator_digest"], "fixture-digest")
            self.assertEqual(len(report["files"]), 2)
            self.assertIsNone(report["files"][0]["probability"])
            self.assertIn("input exceeds context", report["files"][0]["error"])
            self.assertEqual(report["files"][1]["probability"], 0.75)
            self.assertTrue(report["files"][1]["analyzed"])
            self.assertEqual(
                [path for path, _ in requests],
                ["/api/tags", "/v1/systemone", "/v1/systemone"],
            )
            self.assertEqual(
                requests[2][1]["state"],
                {
                    "specification": "Second specification",
                    "java_source": "public class Second {}\n",
                },
            )


if __name__ == "__main__":
    unittest.main()
