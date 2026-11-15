import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from pipeline.clients.anthropic import AnthropicClient
from pipeline.clients.copilot import CopilotClientNoTools
from pipeline.clients.harness import HarnessClient
from pipeline.clients.interface import Client, ClientTimeoutError, timeout_failure_source
from pipeline.clients.lm_studio import LMStudioClient
from pipeline.clients.openai import OpenAIClient
from pipeline.clients.tests.test_anthropic import response as anthropic_response
from pipeline.clients.tests.test_openai import response as openai_response
from pipeline.clients.tests.test_harness import completed as harness_response
from pipeline.helpers import (
    discover_model_directories,
    find_latest_file,
    generation_model_name,
)
from pipeline.prompt_generator.generator import build_prompts
from pipeline.prompt_generator.problems import PROBLEM_CATEGORIES, Problem
from pipeline.preprocessor.preprocessor import preprocess_source
from pipeline.runner.runner import (
    MANIFEST_FILENAME,
    TOTAL_RUNS,
    default_clients,
    java_filename,
    load_prompts,
    main,
    run,
)
from pipeline.verifier.java_verifier import compile_file


class RunnerTests(unittest.TestCase):
    @patch("pipeline.clients.copilot._run_in_workspace")
    def test_harness_saves_only_final_source_and_continues_after_failure(self, execute):
        execute.side_effect = [
            subprocess.CompletedProcess([], 1, "", "model unavailable"),
            harness_response(),
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failures = run(
                [HarnessClient("model", runs=2)],
                [{"datastructure": "Example", "prompt": "generate"}],
                generation_dir=root,
            )
            self.assertEqual(len(failures), 1)
            self.assertEqual((failures[0].client_name, failures[0].run_name), ("copilot-tools", "run-1"))
            self.assertFalse((root / "copilot-tools/model/run-1").exists())
            source = root / "copilot-tools/model/run-2/datastructures/Example.java"
            self.assertEqual(source.read_text(), "public class Example {}\n")
            entry, = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual(entry["client"], "copilot-tools")
            self.assertEqual(entry["java_file"], source.relative_to(root).as_posix())
            self.assertTrue((root / "copilot-tools/model/run-2/results/manifest.json").is_file())

    @patch("pipeline.clients.anthropic.api_key", return_value="synthetic-anthropic-key")
    @patch("pipeline.clients.openai.api_key", return_value="synthetic-openai-key")
    @patch("pipeline.clients.anthropic.Anthropic")
    @patch("pipeline.clients.openai.OpenAI")
    def test_hosted_clients_repeat_runs_with_separate_outputs_and_manifests(
        self, openai, anthropic, openai_key, anthropic_key,
    ):
        openai.return_value.__enter__.return_value.responses.create.return_value = openai_response()
        anthropic.return_value.__enter__.return_value.messages.create.return_value = anthropic_response()
        clients: list[Client] = [
            OpenAIClient("publisher/model", runs=2),
            AnthropicClient("publisher/model", runs=3),
        ]
        prompts = [{"datastructure": "Example", "prompt": "generate"}]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run(clients, prompts, generation_dir=root), [])
            entries = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual(len(entries), 5)
            for client in clients:
                for number in range(1, client.runs + 1):
                    run_dir = root / client.name / "publisher%2Fmodel" / f"run-{number}"
                    self.assertEqual(
                        (run_dir / "datastructures/Example.java").read_text(),
                        "public class Example {}\n",
                    )
                    manifest = run_dir / "results" / MANIFEST_FILENAME
                    entry, = json.loads(manifest.read_text())["generations"]
                    self.assertEqual(entry["client"], client.name)
                    self.assertEqual(entry["model"], "publisher/model")
                    self.assertEqual(entry["run"], f"run-{number}")
        self.assertEqual(openai.return_value.__enter__.return_value.responses.create.call_count, 2)
        self.assertEqual(anthropic.return_value.__enter__.return_value.messages.create.call_count, 3)

    @patch("pipeline.clients.anthropic.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.openai.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.anthropic.Anthropic")
    @patch("pipeline.clients.openai.OpenAI")
    def test_hosted_failure_does_not_save_partial_source_or_block_later_runs(
        self, openai, anthropic, openai_key, anthropic_key,
    ):
        openai.return_value.__enter__.return_value.responses.create.side_effect = [
            openai_response("partial source", status="incomplete"), openai_response(),
        ]
        anthropic.return_value.__enter__.return_value.messages.create.return_value = anthropic_response()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failures = run(
                [OpenAIClient("model", runs=2), AnthropicClient("model")],
                [{"datastructure": "Example", "prompt": "generate"}], generation_dir=root,
            )
            self.assertEqual(len(failures), 1)
            self.assertEqual((failures[0].client_name, failures[0].run_name), ("openai", "run-1"))
            self.assertFalse((root / "openai/model/run-1").exists())
            self.assertTrue((root / "openai/model/run-2/datastructures/Example.java").is_file())
            self.assertTrue((root / "anthropic/model/run-1/datastructures/Example.java").is_file())
            entries = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual(len(entries), 2)

    @patch.object(CopilotClientNoTools, "call", autospec=True)
    @patch.object(LMStudioClient, "call", autospec=True)
    def test_runs_repeat_all_categories_with_isolated_sources_and_manifests(self, lm, copilot):
        clients = [
            CopilotClientNoTools("model", runs=2),
            LMStudioClient("publisher/model", reasoning="off", runs=3),
        ]
        requests = []

        def respond(client, prompt):
            requests.append((client.name, prompt))
            return f"public class Example {{ int sample = {len(requests)}; }}"

        lm.side_effect = copilot.side_effect = respond
        prompts = [
            {"datastructure": "map", "prompt": "map", "category": "datastructures"},
            {"datastructure": "novel", "prompt": "novel", "category": "novel_problems"},
            {
                "datastructure": "advanced", "prompt": "advanced",
                "category": "advanced_datastructures",
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run(clients, prompts, generation_dir=root), [])
            entries = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual(len(entries), 15)
            self.assertEqual(len(list(root.rglob("*.java"))), 15)
            self.assertEqual(len({entry["prompt_file"] for entry in entries}), 1)
            for client in clients:
                model_name = getattr(client, "output_model_name", client.model)
                for number in range(1, client.runs + 1):
                    run_name = f"run-{number}"
                    run_dir = root / client.name / model_name / run_name
                    manifest = run_dir / "results" / MANIFEST_FILENAME
                    mappings = json.loads(manifest.read_text())["generations"]
                    self.assertEqual(len(mappings), len(prompts))
                    self.assertEqual(
                        {entry["category"] for entry in mappings},
                        {prompt["category"] for prompt in prompts},
                    )
                    for entry in mappings:
                        self.assertEqual(entry["run"], run_name)
                        self.assertEqual(entry["model"], client.model)
                        source = (manifest.parent / entry["java_file"]).resolve()
                        self.assertEqual(
                            source, (run_dir / entry["category"] / "Example.java").resolve(),
                        )
                        self.assertTrue(source.is_file())
                        self.assertTrue((manifest.parent / entry["prompt_file"]).is_file())
        self.assertEqual(requests, [
            (client.name, prompt["prompt"])
            for client in clients
            for _ in range(client.runs)
            for prompt in prompts
        ])

    @patch.object(LMStudioClient, "call", autospec=True)
    def test_resumes_each_numbered_run_with_skips_and_never_overwrites(self, call):
        prompts = [
            {"datastructure": "map", "prompt": "map"},
            {"datastructure": "set", "prompt": "set"},
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            call.return_value = "public class Map {}"
            self.assertEqual(run(
                [LMStudioClient("model", runs=2)], prompts[:1], generation_dir=root,
            ), [])
            original_sources = {
                path: path.read_bytes() for path in root.rglob("*.java")
            }
            call.reset_mock()
            call.return_value = "public class Set {}"
            self.assertEqual(run(
                [LMStudioClient("model", runs=2, skip_chats=1)],
                prompts, generation_dir=root,
            ), [])
            self.assertEqual([invocation.args[1] for invocation in call.call_args_list], ["set", "set"])
            entries = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual(len(entries), 4)
            model_dir = root / "lm-studio" / "model"
            self.assertEqual(
                sorted(path.name for path in model_dir.iterdir()), ["run-1", "run-2"],
            )
            for run_name in ("run-1", "run-2"):
                manifest = model_dir / run_name / "results" / MANIFEST_FILENAME
                self.assertEqual(
                    [entry["problem"] for entry in json.loads(manifest.read_text())["generations"]],
                    ["map", "set"],
                )
            manifests = {
                path: path.read_bytes() for path in root.rglob(MANIFEST_FILENAME)
            }
            call.return_value = "public class Map { int changed; }"
            failures = run(
                [LMStudioClient("model", runs=2)], prompts[:1], generation_dir=root,
            )
            self.assertEqual([failure.run_name for failure in failures], ["run-1", "run-2"])
            for path, contents in {**original_sources, **manifests}.items():
                self.assertEqual(path.read_bytes(), contents)

    @patch.object(LMStudioClient, "call", autospec=True)
    def test_increasing_run_count_adds_only_missing_numbered_outputs(self, call):
        prompts = [{"datastructure": "map", "prompt": "map"}]
        call.return_value = "public class Map {}"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run(
                [LMStudioClient("model")], prompts, generation_dir=root,
            ), [])
            source = root / "lm-studio/model/run-1/datastructures/Map.java"
            original = source.read_bytes()
            failures = run(
                [LMStudioClient("model", runs=3)], prompts, generation_dir=root,
            )
            self.assertEqual([failure.run_name for failure in failures], ["run-1"])
            self.assertEqual(source.read_bytes(), original)
            entries = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual([entry["run"] for entry in entries], ["run-1", "run-2", "run-3"])

    @patch.object(CopilotClientNoTools, "call", autospec=True)
    def test_failure_in_one_run_does_not_block_later_runs(self, call):
        call.side_effect = [RuntimeError("unavailable"), "public class Map {}"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failures = run(
                [CopilotClientNoTools("model", runs=2)],
                [{"datastructure": "map", "prompt": "map"}],
                generation_dir=root,
            )
            self.assertEqual(len(failures), 1)
            self.assertEqual(failures[0].run_name, "run-1")
            self.assertEqual(failures[0].error, "unavailable")
            self.assertTrue((root / "copilot-no-tools/model/run-2/datastructures/Map.java").is_file())
            self.assertFalse((root / "copilot-no-tools/model/run-1").exists())

    def test_validates_all_custom_client_run_counts_before_generating(self):
        valid = Mock(spec=["name", "model", "call"])
        for runs in (0, -1, True, False, 1.5, "3", None):
            invalid = Mock(spec=["name", "model", "call", "runs"])
            invalid.runs = runs
            with self.subTest(runs=runs), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with self.assertRaisesRegex(ValueError, "runs must be a positive integer"):
                    run([valid, invalid], [
                        {"datastructure": "map", "prompt": "map"},
                    ], generation_dir=root)
                invalid.call.assert_not_called()
                self.assertEqual(list(root.iterdir()), [])
        valid.call.assert_not_called()

    def test_validates_all_custom_client_skip_run_counts_before_generating(self):
        valid = Mock(spec=["name", "model", "call"])
        for skip_runs in (-1, True, False, 1.5, "1", None, 2):
            invalid = Mock(spec=["name", "model", "call", "runs", "skip_runs"])
            invalid.runs = 2
            invalid.skip_runs = skip_runs
            with self.subTest(skip_runs=skip_runs), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with self.assertRaisesRegex(ValueError, "skip_runs"):
                    run([valid, invalid], [
                        {"datastructure": "map", "prompt": "map"},
                    ], generation_dir=root)
                invalid.call.assert_not_called()
                self.assertEqual(list(root.iterdir()), [])
        valid.call.assert_not_called()

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_exhausted_copilot_timeouts_continue_to_next_prompt(self, subprocess_run):
        subprocess_run.side_effect = [
            subprocess.TimeoutExpired("copilot", 300, output=b"partial Java")
            for _ in range(6)
        ] + [
            subprocess.CompletedProcess(
                args=[], returncode=0, stderr="",
                stdout=json.dumps({
                    "type": "assistant.message",
                    "data": {"content": "public class Set {}"},
                }),
            ),
        ]
        prompts = [
            {"datastructure": "map", "prompt": "generate map"},
            {"datastructure": "set", "prompt": "generate set"},
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failures = run(
                [CopilotClientNoTools("model")], prompts, generation_dir=root,
            )
            self.assertEqual(len(failures), 1)
            self.assertEqual(failures[0].datastructure, "map")
            self.assertIn("exhausted 5 retries", failures[0].error)
            self.assertEqual(sorted(source.name for source in root.rglob("*.java")), ["Set.java", "map.java"])
            placeholder = root / "copilot-no-tools/model/run-1/datastructures/map.java"
            source = placeholder.read_text()
            self.assertTrue(source.startswith("public class map {"))
            self.assertIn("timeout retry exhaustion (6 attempts)", source)
            self.assertNotIn("partial Java", source)
            self.assertEqual(java_filename(source, "different-name"), "map.java")
            entries = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual([entry["problem"] for entry in entries], ["map", "set"])
        self.assertEqual(subprocess_run.call_count, 7)
        self.assertEqual(
            [call.args[0][2] for call in subprocess_run.call_args_list],
            ["generate map"] * 6 + ["generate set"],
        )

    def test_shared_timeout_placeholder_uses_problem_metadata_and_keeps_failure(self):
        client = Mock(spec=["name", "model", "call", "runs"])
        client.name = "client"
        client.model = "model"
        client.runs = 2
        client.call.side_effect = [
            ClientTimeoutError("timeout budget exhausted", attempts=3),
            "public class Recovered {}",
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failures = run([client], [{
                "name": "novel_problems/RetryProblem",
                "datastructure": "RetryProblem",
                "category": "novel_problems",
                "prompt": "A prompt without a Java class name",
            }], generation_dir=root)
            self.assertEqual(len(failures), 1)
            self.assertEqual(failures[0].run_name, "run-1")
            self.assertEqual(failures[0].error, "timeout budget exhausted")
            source = root / "client/model/run-1/novel_problems/RetryProblem.java"
            self.assertIn("public class RetryProblem {", source.read_text())
            self.assertIn("timeout retry exhaustion (3 attempts)", source.read_text())
            self.assertTrue((root / "client/model/run-2/novel_problems/Recovered.java").is_file())
            entry = json.loads((root / MANIFEST_FILENAME).read_text())["generations"][0]
            self.assertEqual(entry["prompt_name"], "novel_problems/RetryProblem")
            self.assertEqual(entry["java_file"], source.relative_to(root).as_posix())
            manifest = root / "client/model/run-1/results/manifest.json"
            self.assertEqual(
                json.loads(manifest.read_text())["generations"][0]["problem"],
                "RetryProblem",
            )

    def test_timeout_placeholder_does_not_overwrite_an_existing_source(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.side_effect = [
            "public class Example {}",
            ClientTimeoutError("timeout budget exhausted", attempts=2),
        ]
        prompts = [{"datastructure": "Example", "prompt": "generate"}]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run([client], prompts, generation_dir=root), [])
            source = root / "client/model/run-1/datastructures/Example.java"
            original = source.read_bytes()
            manifest = (root / MANIFEST_FILENAME).read_bytes()
            failures = run([client], prompts, generation_dir=root)
            self.assertEqual(len(failures), 1)
            self.assertIn("File exists", failures[0].error)
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual((root / MANIFEST_FILENAME).read_bytes(), manifest)

    def test_timeout_placeholder_rejects_problem_names_that_cannot_be_class_names(self):
        for name in ("../Escape", "name with spaces", "Problem\npublic class Injected"):
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, "Java identifier"):
                    timeout_failure_source(name, 2)

    def test_timeout_placeholder_fails_compilation_after_preprocessing(self):
        source = timeout_failure_source("TimeoutProblem", 3)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / java_filename(source, "other-name")
            self.assertEqual(path.name, "TimeoutProblem.java")
            path.write_text(preprocess_source(source), encoding="utf-8")
            report = compile_file(path)
            self.assertFalse(report["compiled"])
            self.assertNotEqual(report["exit_code"], 0)

    def test_manifests_link_shared_corpus_and_different_class_names_across_models(self):
        record = {
            "name": "datastructures/map",
            "category": "datastructures",
            "datastructure": "map",
            "source": "benchmark#map",
            "prompt": "Implement a shared map.",
        }
        clients = []
        for model, class_name in (("first", "ConcurrentMap"), ("second", "SafeTable")):
            client = Mock(spec=["name", "model", "call"])
            client.name = "client"
            client.model = model
            client.call.return_value = f"public class {class_name} {{}}"
            clients.append(client)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prompt_file = root / "prompts" / "source.json"
            prompt_file.parent.mkdir()
            prompt_file.write_text(json.dumps({"prompts": [record]}))
            self.assertEqual(run(clients, load_prompts(prompt_file), generation_dir=root), [])
            manifest = json.loads((root / MANIFEST_FILENAME).read_text())
            self.assertEqual(manifest, {"generations": [
                {
                    "prompt_name": "datastructures/map",
                    "problem": "map",
                    "category": "datastructures",
                    "client": "client",
                    "model": model,
                    "run": "run-1",
                    "java_file": f"client/{model}/run-1/datastructures/{class_name}.java",
                    "prompt_file": "prompts/source.json",
                }
                for model, class_name in (("first", "ConcurrentMap"), ("second", "SafeTable"))
            ]})
            for model, class_name in (("first", "ConcurrentMap"), ("second", "SafeTable")):
                source = root / "client" / model / "run-1" / "datastructures" / f"{class_name}.java"
                self.assertTrue(source.is_file())
                model_manifest = source.parent.parent / "results" / MANIFEST_FILENAME
                entries = json.loads(model_manifest.read_text())["generations"]
                self.assertEqual(len(entries), 1)
                self.assertEqual(entries[0]["model"], model)
                self.assertEqual(entries[0]["prompt_name"], record["name"])
                self.assertEqual(
                    (model_manifest.parent / entries[0]["java_file"]).resolve(),
                    source.resolve(),
                )
                self.assertEqual(
                    (model_manifest.parent / entries[0]["prompt_file"]).resolve(),
                    prompt_file.resolve(),
                )
            self.assertEqual(list(root.rglob("*.prompt.json")), [])

    def test_loaded_prompt_keeps_its_original_file_even_when_a_newer_corpus_exists(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.return_value = "public class Map {}"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            corpus = root / "prompts" / "original.json"
            corpus.parent.mkdir()
            corpus.write_text(json.dumps({"prompts": [{
                "name": "datastructures/map", "datastructure": "map", "prompt": "Original",
            }]}))
            prompts = load_prompts(corpus)
            (corpus.parent / "newer.json").write_text('{"prompts":[]}')
            generation = root / "Generations" / "run"
            self.assertEqual(run([client], prompts, generation_dir=generation), [])
            entry = json.loads((generation / MANIFEST_FILENAME).read_text())["generations"][0]
            self.assertEqual(entry["prompt_file"], "../../prompts/original.json")
            self.assertEqual(entry["prompt_name"], "datastructures/map")
            client.call.assert_called_once_with("Original")

    def test_changed_prompt_is_rejected_instead_of_linking_to_a_different_specification(self):
        client = Mock(spec=["name", "model", "call"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            corpus = root / "source.json"
            corpus.write_text(json.dumps({"prompts": [{
                "datastructure": "map", "prompt": "Original",
            }]}))
            prompts = load_prompts(corpus)
            prompts[0]["prompt"] = "Changed"
            failures = run([client], prompts, generation_dir=root / "generation")
            self.assertEqual(len(failures), 1)
            self.assertIn("does not match its source", failures[0].error)
            client.call.assert_not_called()

    def test_manifest_preserves_previous_mappings_when_resuming_generation(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.side_effect = ["public class Map {}", "public class Set {}"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run([client], [
                {"datastructure": "map", "prompt": "Implement a map."},
            ], generation_dir=root), [])
            manifest_path = root / MANIFEST_FILENAME
            first_entry = json.loads(manifest_path.read_text())["generations"][0]
            self.assertEqual(run([client], [
                {"datastructure": "set", "prompt": "Implement a set."},
            ], generation_dir=root), [])
            entries = json.loads(manifest_path.read_text())["generations"]
            self.assertEqual(entries[0], first_entry)
            self.assertEqual(
                [entry["prompt_name"] for entry in entries],
                ["datastructures/map", "datastructures/set"],
            )
            for entry in entries:
                self.assertTrue((root / entry["java_file"]).is_file())
                self.assertTrue((root / entry["prompt_file"]).is_file())
            model_manifest = root / "client" / "model" / "run-1" / "results" / MANIFEST_FILENAME
            self.assertEqual(
                [entry["prompt_name"] for entry in json.loads(model_manifest.read_text())["generations"]],
                ["datastructures/map", "datastructures/set"],
            )

    def test_manifest_write_failure_preserves_previous_manifest_and_removes_new_source(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.side_effect = ["public class Map {}", "public class Set {}"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run([client], [
                {"datastructure": "map", "prompt": "map"},
            ], generation_dir=root), [])
            manifest_path = root / MANIFEST_FILENAME
            original = manifest_path.read_bytes()
            with patch("pipeline.runner.runner.Path.replace", side_effect=OSError("disk full")):
                failures = run([client], [
                    {"datastructure": "set", "prompt": "set"},
                ], generation_dir=root)
            self.assertEqual(len(failures), 1)
            self.assertIn("disk full", failures[0].error)
            self.assertEqual(manifest_path.read_bytes(), original)
            self.assertFalse(any(root.glob(".manifest-*")))
            self.assertEqual([p.name for p in root.rglob("*.java")], ["Map.java"])
            self.assertEqual(list(root.rglob("*.prompt.json")), [])

    def test_model_manifest_failure_rolls_back_the_generation_manifest(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.side_effect = ["public class Map {}", "public class Set {}"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run([client], [
                {"datastructure": "map", "prompt": "map"},
            ], generation_dir=root), [])
            manifests = [
                root / MANIFEST_FILENAME,
                root / "client/model/run-1/results" / MANIFEST_FILENAME,
            ]
            originals = [path.read_bytes() for path in manifests]
            replace = Path.replace

            def fail_model_manifest(path, target):
                if target == manifests[1]:
                    raise OSError("model manifest unavailable")
                return replace(path, target)

            with patch.object(Path, "replace", autospec=True, side_effect=fail_model_manifest):
                failures = run([client], [
                    {"datastructure": "set", "prompt": "set"},
                ], generation_dir=root)
            self.assertEqual(len(failures), 1)
            self.assertEqual([path.read_bytes() for path in manifests], originals)
            self.assertFalse(any(root.rglob("Set.java")))

    def test_invalid_existing_manifest_is_not_overwritten_or_sent_to_clients(self):
        client = Mock(spec=["name", "model", "call"])
        for contents in ('not json', '[]', '{"generations": [{}]}'):
            with self.subTest(contents=contents), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                manifest_path = root / MANIFEST_FILENAME
                manifest_path.write_text(contents)
                with self.assertRaises(ValueError):
                    run([client], [
                        {"datastructure": "map", "prompt": "map"},
                    ], generation_dir=root)
                self.assertEqual(manifest_path.read_text(), contents)
                self.assertEqual(list(root.iterdir()), [manifest_path])
        client.call.assert_not_called()

    def test_legacy_prompts_get_a_traceable_name_without_mutating_input(self):
        record = {"datastructure": "map", "prompt": "Implement a map."}
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.return_value = "class Map {}"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run([client], [record], generation_dir=root), [])
            entry = json.loads((root / MANIFEST_FILENAME).read_text())["generations"][0]
            self.assertEqual(entry["prompt_name"], "datastructures/map")
            self.assertEqual(entry["category"], "datastructures")
            self.assertEqual(entry["java_file"], "client/model/run-1/datastructures/map.java")
            saved = load_prompts(root / entry["prompt_file"])
            self.assertEqual(saved[0]["prompt"], record["prompt"])
            self.assertEqual(list(root.rglob("*.prompt.json")), [])
        self.assertEqual(record, {"datastructure": "map", "prompt": "Implement a map."})

    def test_duplicate_or_invalid_names_are_rejected_before_client_calls(self):
        client = Mock(spec=["name", "model", "call"])
        for name in ("duplicate", "", " ", None):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                records = [
                    {"name": name, "datastructure": structure, "prompt": structure}
                    for structure in ("map", "set")
                ]
                with self.assertRaisesRegex(ValueError, "(Duplicate|Invalid) prompt name"):
                    run([client], records, generation_dir=Path(directory))
                self.assertEqual(list(Path(directory).iterdir()), [])
        client.call.assert_not_called()

    def test_old_sidecar_is_left_untouched_and_new_programs_do_not_create_sidecars(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.side_effect = ["public class Map {}", "public class Set {}"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_dir = root / "client" / "model" / "run-1" / "datastructures"
            source_dir.mkdir(parents=True)
            metadata = source_dir / "Map.prompt.json"
            metadata.write_text('{"name": "existing"}\n')
            original = metadata.read_bytes()
            failures = run([client], [
                {"datastructure": "map", "prompt": "map"},
                {"datastructure": "set", "prompt": "set"},
            ], generation_dir=root)
            self.assertEqual(failures, [])
            self.assertTrue((source_dir / "Map.java").exists())
            self.assertEqual(metadata.read_bytes(), original)
            self.assertTrue((source_dir / "Set.java").is_file())
            self.assertFalse((source_dir / "Set.prompt.json").exists())
            entries = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual(
                [entry["prompt_name"] for entry in entries],
                ["datastructures/map", "datastructures/set"],
            )

    def test_registered_category_is_generated_and_discovered_without_pipeline_changes(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.return_value = "public class Barrier {}"
        with patch.dict(PROBLEM_CATEGORIES, {
            "coordination": [Problem("Barrier", "Implement a reusable barrier.")],
        }), tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prompts = [p for p in build_prompts() if p["category"] == "coordination"]
            self.assertEqual(run([client], prompts, generation_dir=root), [])
            source_dir = root / "client" / "model" / "run-1" / "coordination"
            self.assertEqual(
                (source_dir / "Barrier.java").read_text(),
                "public class Barrier {}\n",
            )
            self.assertEqual(discover_model_directories(root), [source_dir])
            self.assertEqual(generation_model_name(source_dir), "model")
            client.call.assert_called_once_with(prompts[0]["prompt"])

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_reasoning_off_uses_separate_folder_and_preserves_existing_outputs(self, curl):
        curl.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stderr="",
            stdout=json.dumps({"output": [
                {"type": "message", "content": "public class Map { int newValue; }"},
            ]}),
        )
        client = LMStudioClient("publisher/model", reasoning="off")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old_dir = root / "lm-studio/publisher%2Fmodel/datastructures"
            old_dir.mkdir(parents=True)
            old_source = old_dir / "Map.java"
            old_source.write_text("public class Map { int oldValue; }\n")
            original = old_source.read_bytes()
            self.assertEqual(run(
                [client], [{"datastructure": "map", "prompt": "generate map"}],
                generation_dir=root,
            ), [])
            new_dir = root / "lm-studio/publisher%2Fmodel@reasoning-off/run-1/datastructures"
            self.assertEqual(
                (new_dir / "Map.java").read_text(),
                "public class Map { int newValue; }\n",
            )
            self.assertEqual(old_source.read_bytes(), original)
            self.assertEqual(set(discover_model_directories(root)), {old_dir, new_dir})
        payload = json.loads(curl.call_args.kwargs["input"])
        self.assertEqual(payload["model"], "publisher/model")
        self.assertEqual(payload["reasoning"], "off")

    @patch("builtins.print")
    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_exhausted_timeout_retries_save_placeholder_and_continue_next_prompt(self, curl, output):
        timeout = subprocess.CompletedProcess(
            args=[], returncode=28, stdout="partial Java", stderr="operation timed out"
        )
        curl.side_effect = [
            timeout,
            timeout,
            subprocess.CompletedProcess(
                args=[], returncode=0, stderr="",
                stdout=json.dumps({"output": [
                    {"type": "message", "content": "public class Set {}"},
                ]}),
            ),
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failures = run(
                [LMStudioClient("model", timeout_retries=1)],
                [
                    {"datastructure": "map", "prompt": "first"},
                    {"datastructure": "set", "prompt": "second"},
                ],
                generation_dir=root,
            )
            self.assertEqual(len(failures), 1)
            self.assertEqual(failures[0].datastructure, "map")
            self.assertIn("after 2 attempt", failures[0].error)
            self.assertEqual(sorted(path.name for path in root.rglob("*.java")), ["Set.java", "map.java"])
            placeholder = root / "lm-studio/model/run-1/datastructures/map.java"
            self.assertIn("timeout retry exhaustion (2 attempts)", placeholder.read_text())
            entries = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual([entry["problem"] for entry in entries], ["map", "set"])
        self.assertEqual(curl.call_count, 3)

    def test_existing_java_file_is_preserved_and_collision_is_reported(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.side_effect = ["public class Map { int replacement; }", "public class Set {}"]
        prompts = [
            {"datastructure": name, "prompt": name} for name in ("Map", "Set")
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_dir = root / "client" / "model" / "run-1" / "datastructures"
            source_dir.mkdir(parents=True)
            existing = source_dir / "Map.java"
            existing.write_bytes(b"public class Map { int original; }\r\n")
            original = existing.read_bytes()
            modified = existing.stat().st_mtime_ns
            with patch("builtins.print") as output:
                failures = run([client], prompts, generation_dir=root)
            self.assertEqual(existing.read_bytes(), original)
            self.assertEqual(existing.stat().st_mtime_ns, modified)
            self.assertEqual(len(failures), 1)
            self.assertEqual(failures[0].datastructure, "Map")
            self.assertIn("File exists", failures[0].error)
            self.assertTrue(any(
                invocation.args[0].startswith("Failed client/model/run-1/datastructures/Map:")
                for invocation in output.call_args_list
            ))
            self.assertEqual((source_dir / "Set.java").read_text(), "public class Set {}\n")

    @patch("pipeline.runner.runner.run", return_value=[])
    @patch("pipeline.runner.runner.default_clients", return_value=[])
    @patch("pipeline.runner.runner.load_prompts", return_value=[])
    def test_cli_generates_into_selected_folder(self, load, clients, generate):
        self.assertEqual(main([
            "--prompt-file", "prompts.json", "--generation-dir", "Generations/date:chosen",
        ]), 0)
        load.assert_called_once_with(Path("prompts.json"))
        generate.assert_called_once_with(
            [], [], Path("Generations"), generation_dir=Path("Generations/date:chosen"),
        )

    def test_cli_rejects_conflicting_generation_destinations(self):
        with patch("sys.stderr"), self.assertRaises(SystemExit) as error:
            main(["--output-dir", "root", "--generation-dir", "chosen"])
        self.assertEqual(error.exception.code, 2)

    @patch("builtins.print")
    @patch.object(LMStudioClient, "call", autospec=True)
    def test_skip_chats_is_per_model_across_categories_and_logs_skips(self, call, output):
        call.side_effect = lambda client, prompt: f"public class {prompt} {{}}"
        clients = [
            LMStudioClient("first-model", skip_chats=2),
            LMStudioClient("second-model", skip_chats=1),
        ]
        other = Mock(spec=["name", "model", "call"])
        other.name = "copilot"
        other.model = "other"
        other.call.side_effect = lambda prompt: f"public class {prompt} {{}}"
        prompts = [
            {"datastructure": "Map", "prompt": "Map"},
            {
                "datastructure": "NovelCounter", "prompt": "NovelCounter",
                "category": "novel_problems",
            },
            {
                "datastructure": "NovelQueue", "prompt": "NovelQueue",
                "category": "novel_problems",
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run([*clients, other], prompts, generation_dir=root), [])
            self.assertEqual(
                {p.relative_to(root).as_posix() for p in root.rglob("*.java")},
                {
                    "lm-studio/first-model/run-1/novel_problems/NovelQueue.java",
                    "lm-studio/second-model/run-1/novel_problems/NovelCounter.java",
                    "lm-studio/second-model/run-1/novel_problems/NovelQueue.java",
                    "copilot/other/run-1/datastructures/Map.java",
                    "copilot/other/run-1/novel_problems/NovelCounter.java",
                    "copilot/other/run-1/novel_problems/NovelQueue.java",
                },
            )
        self.assertEqual(
            [(invocation.args[0].model, invocation.args[1]) for invocation in call.call_args_list],
            [
                ("first-model", "NovelQueue"),
                ("second-model", "NovelCounter"),
                ("second-model", "NovelQueue"),
            ],
        )
        self.assertEqual(
            [invocation.args[0] for invocation in other.call.call_args_list],
            ["Map", "NovelCounter", "NovelQueue"],
        )
        skipped = [
            invocation for invocation in output.call_args_list
            if invocation.args[0].startswith("Skipping ")
        ]
        self.assertEqual(len(skipped), 3)
        self.assertEqual(
            skipped[0].args[0],
            "Skipping lm-studio/first-model/run-1/datastructures/Map (1/3): skip_chats=2",
        )
        self.assertTrue(all(invocation.kwargs == {"flush": True} for invocation in skipped))

    @patch.object(LMStudioClient, "call", autospec=True)
    def test_skip_boundaries_and_reusing_client_reset_per_run(self, call):
        call.side_effect = lambda client, prompt: f"public class {prompt} {{}}"
        prompts = [
            {"datastructure": name, "prompt": name} for name in ("Map", "Set", "Queue")
        ]
        for count in (0, 1, 3, 5):
            with self.subTest(skip_chats=count), tempfile.TemporaryDirectory() as directory:
                call.reset_mock()
                client = LMStudioClient("model", skip_chats=count)
                expected = ["Map", "Set", "Queue"][count:]
                for run_name in ("first", "second"):
                    root = Path(directory) / run_name
                    self.assertEqual(run([client], prompts, generation_dir=root), [])
                    self.assertEqual(
                        {p.name for p in root.rglob("*.java")},
                        {f"{name}.java" for name in expected},
                    )
                self.assertEqual(
                    [invocation.args[1] for invocation in call.call_args_list],
                    expected * 2,
                )

    @patch.object(LMStudioClient, "call", autospec=True)
    def test_skip_runs_is_per_client_and_preserves_run_numbers(self, call):
        call.side_effect = lambda client, prompt: f"public class {prompt} {{}}"
        clients = [
            LMStudioClient("first-model", runs=3, skip_runs=1),
            LMStudioClient("second-model", runs=2),
        ]
        prompt = [{"datastructure": "Map", "prompt": "Map"}]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run(clients, prompt, generation_dir=root), [])
            self.assertEqual(
                {path.relative_to(root).as_posix() for path in root.rglob("*.java")},
                {
                    "lm-studio/first-model/run-2/datastructures/Map.java",
                    "lm-studio/first-model/run-3/datastructures/Map.java",
                    "lm-studio/second-model/run-1/datastructures/Map.java",
                    "lm-studio/second-model/run-2/datastructures/Map.java",
                },
            )
            entries = json.loads((root / MANIFEST_FILENAME).read_text())["generations"]
            self.assertEqual(
                [(entry["model"], entry["run"]) for entry in entries],
                [
                    ("first-model", "run-2"),
                    ("first-model", "run-3"),
                    ("second-model", "run-1"),
                    ("second-model", "run-2"),
                ],
            )
        self.assertEqual(
            [invocation.args[0].model for invocation in call.call_args_list],
            ["first-model", "first-model", "second-model", "second-model"],
        )

    def test_finds_latest_prompt_file_and_supports_explicit_file(self):
        with tempfile.TemporaryDirectory() as directory:
            prompt_dir = Path(directory)
            older = prompt_dir / "date:older.json"
            newer = prompt_dir / "date:newer.json"
            older.write_text(json.dumps({"prompts": []}), encoding="utf-8")
            newer.write_text(
                json.dumps(
                    {"prompts": [{"datastructure": "map", "prompt": "p"}]}
                ),
                encoding="utf-8",
            )
            older.touch()
            newer.touch()

            self.assertEqual(find_latest_file(prompt_dir, "date:*.json"), newer)
            self.assertEqual(load_prompts(newer)[0]["datastructure"], "map")

    def test_writes_expected_java_path(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "copilot"
        client.model = "gpt-5.6-luna"
        client.call.return_value = "public final class ConcurrentMap {}"
        prompt = {"datastructure": "map", "prompt": "generate map"}

        with tempfile.TemporaryDirectory() as directory:
            failures = run([client], [prompt], Path(directory))

            self.assertEqual(failures, [])
            run_directories = list(Path(directory).glob("date:*"))
            self.assertEqual(len(run_directories), 1)
            self.assertRegex(
                run_directories[0].name,
                r"^date:\d{4}-\d{2}-\d{2}T\d{2}-\d{2}$",
            )
            output = (
                run_directories[0]
                / "copilot"
                / "gpt-5.6-luna"
                / "run-1"
                / "datastructures"
                / "ConcurrentMap.java"
            )
            self.assertEqual(
                output.read_text(encoding="utf-8"),
                "public final class ConcurrentMap {}\n",
            )

    def test_falls_back_to_prompt_name_without_public_type(self):
        self.assertEqual(java_filename("class Map {}", "map"), "map.java")

    @patch("pipeline.runner.runner.timestamp_now")
    def test_reuses_timestamp_across_prompts_and_clients(self, clock):
        clock.return_value = "date:2026-10-14T22-29"
        clients = []
        for model in ("first-model", "second-model"):
            client = Mock(spec=["name", "model", "call"])
            client.name = "copilot"
            client.model = model

            def respond(prompt):
                clock.return_value = "date:2026-10-14T22-30"
                return f"public class {prompt} {{}}"

            client.call.side_effect = respond
            clients.append(client)
        prompts = [
            {"datastructure": name, "prompt": name} for name in ("Map", "Set")
        ]

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(run(clients, prompts, root), [])
            self.assertEqual(
                list(root.iterdir()), [root / "date:2026-10-14T22-29"]
            )
            for client in clients:
                for name in ("Map", "Set"):
                    source = (
                        root / "date:2026-10-14T22-29" / "copilot" / client.model
                        / "run-1" / "datastructures" / f"{name}.java"
                    )
                    self.assertEqual(
                        source.read_text(encoding="utf-8"),
                        f"public class {name} {{}}\n",
                    )
        clock.assert_called_once_with()

    @patch("pipeline.runner.runner.run", return_value=[])
    @patch("pipeline.runner.runner.default_clients")
    @patch("pipeline.runner.runner.load_prompts")
    @patch("pipeline.runner.runner.timestamp_now")
    def test_main_captures_timestamp_before_loading_prompts(
        self, clock, load, clients, generate
    ):
        clock.return_value = "date:2026-10-14T22-29"
        prompts = [{"datastructure": "map", "prompt": "generate map"}]

        def load_after_minute_changes(prompt_file):
            clock.assert_called_once_with()
            clock.return_value = "date:2026-10-14T22-30"
            return prompts

        load.side_effect = load_after_minute_changes
        self.assertEqual(main(["--output-dir", "custom-output"]), 0)
        generate.assert_called_once_with(
            clients.return_value,
            prompts,
            Path("custom-output"),
            generation_dir=Path("custom-output") / "date:2026-10-14T22-29",
        )
        clock.assert_called_once_with()

    def test_default_clients_use_current_anthropic_model_ids(self):
        self.assertEqual(
            default_clients(),
            [
                AnthropicClient(model="claude-haiku-5-5", runs=TOTAL_RUNS, skip_runs=1),
                AnthropicClient(model="claude-sonnet-5-5", runs=TOTAL_RUNS),
                OpenAIClient(model="gpt-6.1-terra", runs=TOTAL_RUNS),
            ],
        )

    @patch("builtins.print")
    def test_reports_progress_before_call_and_after_saving(self, output):
        client = Mock(spec=["name", "model", "call"])
        client.name = "lm-studio"
        client.model = "model"

        def respond(prompt):
            output.assert_called_once_with(
                "Generating lm-studio/model/run-1/datastructures/map (1/1)...", flush=True
            )
            return "public class Map {}"

        client.call.side_effect = respond
        with tempfile.TemporaryDirectory() as directory:
            failures = run(
                [client],
                [{"datastructure": "map", "prompt": "generate map"}],
                Path(directory),
            )
            self.assertEqual(failures, [])
            saved = next(Path(directory).rglob("Map.java"))
            self.assertEqual(saved.read_text(), "public class Map {}\n")
            self.assertEqual(output.call_count, 2)
            self.assertEqual(output.call_args.args, (f"Saved {saved}",))
            self.assertEqual(output.call_args.kwargs, {"flush": True})

    @patch("builtins.print")
    def test_reports_failure_before_next_prompt(self, output):
        client = Mock(spec=["name", "model", "call"])
        client.name = "lm-studio"
        client.model = "model"

        def respond(prompt):
            if prompt == "first":
                raise RuntimeError("request timed out")
            self.assertEqual(
                output.call_args_list[-2].args,
                ("Failed lm-studio/model/run-1/datastructures/map: request timed out",),
            )
            self.assertEqual(output.call_args_list[-2].kwargs, {"flush": True})
            return "public class Set {}"

        client.call.side_effect = respond
        with tempfile.TemporaryDirectory() as directory:
            failures = run(
                [client],
                [
                    {"datastructure": "map", "prompt": "first"},
                    {"datastructure": "set", "prompt": "second"},
                ],
                Path(directory),
            )
        self.assertEqual(len(failures), 1)

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_lm_studio_model_path_and_analysis_discovery(self, curl):
        curl.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stderr="",
            stdout=json.dumps({"output": [
                {"type": "reasoning", "content": "must not enter the Java file"},
                {"type": "message", "content": "public class ConcurrentMap {}"},
            ]}),
        )
        client = LMStudioClient("publisher/model")
        with tempfile.TemporaryDirectory() as directory:
            failures = run(
                [client],
                [{"datastructure": "map", "prompt": "generate map"}],
                Path(directory),
            )
            self.assertEqual(failures, [])
            (date_root,) = Path(directory).iterdir()
            model_dir = date_root / "lm-studio" / "publisher%2Fmodel" / "run-1"
            self.assertEqual(
                discover_model_directories(date_root), [model_dir / "datastructures"]
            )
            self.assertEqual(
                (model_dir / "datastructures" / "ConcurrentMap.java").read_text(
                    encoding="utf-8"
                ),
                "public class ConcurrentMap {}\n",
            )
        payload = json.loads(curl.call_args.kwargs["input"])
        self.assertEqual(payload["model"], "publisher/model")
        self.assertEqual(payload["input"], "generate map")

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_continues_after_lm_studio_failure_and_runs_other_clients(self, curl):
        curl.side_effect = [
            subprocess.CompletedProcess(
                args=[], returncode=0, stdout='{"output": []}', stderr=""
            ),
            subprocess.CompletedProcess(
                args=[], returncode=0, stderr="",
                stdout=json.dumps({"output": [
                    {"type": "message", "content": "public class ConcurrentSet {}"},
                ]}),
            ),
        ]
        other = Mock(spec=["name", "model", "call"])
        other.name = "copilot"
        other.model = "gpt-5.6-luna"
        other.call.return_value = "class Implementation {}"
        prompts = [
            {"datastructure": "map", "prompt": "first"},
            {"datastructure": "set", "prompt": "second"},
        ]
        with tempfile.TemporaryDirectory() as directory:
            failures = run(
                [LMStudioClient("model"), other], prompts, Path(directory)
            )
            self.assertEqual(len(failures), 1)
            self.assertEqual(failures[0].client_name, "lm-studio")
            self.assertEqual(failures[0].datastructure, "map")
            self.assertIn("nonempty text", failures[0].error)
            (date_root,) = Path(directory).iterdir()
            self.assertTrue(
                (
                    date_root / "lm-studio" / "model"
                    / "run-1"
                    / "datastructures" / "ConcurrentSet.java"
                ).is_file()
            )
            for structure in ("map", "set"):
                self.assertTrue(
                    (
                        date_root / "copilot" / other.model
                        / "run-1"
                        / "datastructures" / f"{structure}.java"
                    ).is_file()
                )
        self.assertEqual(other.call.call_count, 2)

    def test_continues_after_client_failure(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "copilot"
        client.model = "gpt-5.6-luna"
        client.call.side_effect = [RuntimeError("failed"), "second response"]
        prompts = [
            {"datastructure": "map", "prompt": "first"},
            {"datastructure": "set", "prompt": "second"},
        ]

        with tempfile.TemporaryDirectory() as directory:
            failures = run([client], prompts, Path(directory))

            self.assertEqual(len(failures), 1)
            self.assertEqual(failures[0].datastructure, "map")
            run_directories = list(Path(directory).glob("date:*"))
            self.assertTrue(
                (
                    run_directories[0]
                    / "copilot"
                    / "gpt-5.6-luna"
                    / "run-1"
                    / "datastructures"
                    / "set.java"
                ).exists()
            )

    def test_categories_isolate_same_public_class_in_explicit_generation(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        responses = [
            "public class Example { int structure; }",
            "public class Example { int novel; }",
        ]
        client.call.side_effect = responses
        prompts = [
            {"datastructure": "map", "prompt": "structure", "category": "datastructures"},
            {"datastructure": "Example", "prompt": "novel", "category": "novel_problems"},
        ]
        with tempfile.TemporaryDirectory() as directory:
            generation = Path(directory) / "date:explicit"
            self.assertEqual(run([client], prompts, generation_dir=generation), [])
            for category, response in zip(("datastructures", "novel_problems"), responses):
                source = generation / "client" / "model" / "run-1" / category / "Example.java"
                self.assertEqual(source.read_text(), response + "\n")
            self.assertEqual(len(list(generation.rglob("*.java"))), 2)

    def test_unknown_category_is_rejected_before_any_client_calls(self):
        client = Mock(spec=["name", "model", "call"])
        for category in ("unknown", "../escape", "", None):
            with self.subTest(category=category):
                with tempfile.TemporaryDirectory() as directory:
                    with self.assertRaisesRegex(ValueError, "Unknown prompt category"):
                        run(
                            [client],
                            [
                                {"datastructure": "map", "prompt": "valid"},
                                {
                                    "datastructure": "problem", "prompt": "invalid",
                                    "category": category,
                                },
                            ],
                            Path(directory),
                        )
                    self.assertEqual(list(Path(directory).iterdir()), [])
        client.call.assert_not_called()

    def test_novel_failure_retains_category_and_continues(self):
        client = Mock(spec=["name", "model", "call"])
        client.name = "client"
        client.model = "model"
        client.call.side_effect = [RuntimeError("failed"), "class Problem {}"]
        prompts = [
            {"datastructure": name, "prompt": name, "category": "novel_problems"}
            for name in ("first", "second")
        ]
        with tempfile.TemporaryDirectory() as directory:
            failures = run([client], prompts, generation_dir=Path(directory))
            self.assertEqual(len(failures), 1)
            self.assertEqual(failures[0].category, "novel_problems")
            self.assertEqual(failures[0].datastructure, "first")
            self.assertTrue(
                (Path(directory) / "client" / "model" / "run-1" / "novel_problems" / "second.java")
                .is_file()
            )


if __name__ == "__main__":
    unittest.main()
