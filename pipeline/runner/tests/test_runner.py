import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from pipeline.helpers import find_latest_file
from pipeline.runner.runner import java_filename, load_prompts, run


class RunnerTests(unittest.TestCase):
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
        client = Mock()
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
                / "ConcurrentMap.java"
            )
            self.assertEqual(
                output.read_text(encoding="utf-8"),
                "public final class ConcurrentMap {}\n",
            )

    def test_falls_back_to_prompt_name_without_public_type(self):
        self.assertEqual(java_filename("class Map {}", "map"), "map.java")

    def test_continues_after_client_failure(self):
        client = Mock()
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
                    / "set.java"
                ).exists()
            )


if __name__ == "__main__":
    unittest.main()
